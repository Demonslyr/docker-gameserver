properties([pipelineTriggers([githubPush()])])
node {
    jenkinsGitCredentials = 'jenkinsGitHubSvc'

    stage('setup') {
        git url: 'https://github.com/Demonslyr/docker-gameserver.git', branch: 'atriarch', credentialsId: jenkinsGitCredentials
        checkout scm
        currentBuild.description = "${Branch}"
        dockerRepo = "${DockerRepo}"
        dockerCredId = "${DockerCredentials}"
        imageNamePrefix = "${ImageNamePrefix}"
        gitOpsRepo = "${GitOpsRepo}"
        gitOpsBranch = "${GitOpsBranch}"
        pathToDeploymentYaml = "${PathToDeploymentYaml}"
        pathToDockerfileDirectory = "${pathToDockerfileDirectory}"
        apiRepoUrl = "${EdgeApiRepoUrl}"
    }

    stage('build game images') {
      dockerfiles = sh(returnStdout: true, script: "ls ${pathToDockerfileDirectory}").trim().split('\n')
          withCredentials([usernamePassword(usernameVariable: 'DOCKER_USER', passwordVariable: 'DOCKER_PASS', credentialsId: dockerCredId)]) {
                sh "echo ${DOCKER_PASS} | docker login ${dockerRepo} --username ${DOCKER_USER} --password-stdin"
        for (dockerfile in dockerfiles) {
            appName = dockerfile.replace('Dockerfile.', '') // strip off the dockerfile prefix
            localGameImageTag = "${dockerRepo}/${imageNamePrefix}${appName}:game-intermediate"

            // Build the game image locally
            sh """
            docker build -t ${localGameImageTag} -f ${pathToDockerfileDirectory}/${dockerfile} .
            """
        }
      }
    }
// Can these two steps be combined?
stage('build and push final images with API') {
    dir('Atriarch.GameHosting.Edge') {
        git url: apiRepoUrl, branch: 'main', credentialsId: jenkinsGitCredentials

        withCredentials([usernamePassword(usernameVariable: 'DOCKER_USER', passwordVariable: 'DOCKER_PASS', credentialsId: dockerCredId)]) {
            sh "echo ${DOCKER_PASS} | docker login ${dockerRepo} --username ${DOCKER_USER} --password-stdin"

            sh ls
            for (dockerfile in dockerfiles) {
                appName = dockerfile.replace('Dockerfile.', '') // strip off the dockerfile prefix
                localGameImageTag = "${dockerRepo}/${imageNamePrefix}${appName}:game-intermediate"
                finalImageName = "${dockerRepo}/${imageNamePrefix}${appName}:v1.0.${BUILD_NUMBER}"

                // Build the final image with the API, specifying the correct path to the Dockerfile
                sh """
                docker build --build-arg BASE_IMAGE=${localGameImageTag} \
                             -t ${finalImageName} \
                             -f Atriarch.GameHosting.Edge/Dockerfile Atriarch.GameHosting.Edge
                docker push ${finalImageName}
                """
            }
        }
    }
}

    stage('updateGitOpsRepo') {
        dir('GitOps') { // Clone the repo in a new workspace to avoid conflicts
            git url: "https://github.com/${gitOpsRepo}", branch: gitOpsBranch, credentialsId: jenkinsGitCredentials

            sh """
            find ./atriarch-game-hosting/servers -type f -name '*.yml' | while read file; do
                python3 -c "
import yaml
import re

def update_image_version(file_path, repo, prefix, new_version):
    with open(file_path, 'r') as f:
        docs = yaml.safe_load_all(f)
        updated_docs = []
        image_pattern = re.compile(f'{repo}/{prefix}([^:]*):v\\d+\\.\\d+\\.\\d+')
        for doc in docs:
            if 'kind' in doc and doc['kind'] == 'Deployment':
                containers = doc.get('spec', {}).get('template', {}).get('spec', {}).get('containers', [])
                for container in containers:
                    if 'image' in container:
                        image = container['image']
                        if image_pattern.match(image):
                            new_image = image_pattern.sub(fr'{repo}/{prefix}\\1:{new_version}', image)
                            container['image'] = new_image
            updated_docs.append(doc)
    with open(file_path, 'w') as f:
        yaml.safe_dump_all(updated_docs, f, explicit_start=True)

new_version = 'v1.0.${BUILD_NUMBER}'
repo = '${dockerRepo}'
prefix = '${imageNamePrefix}'
update_image_version('\$file', repo, prefix, new_version)
"
            done
            """

            sh 'git add ./atriarch-game-hosting/servers/*.yml'
            sh "git commit -m \"Update server versions to ${BUILD_NUMBER}\""

            withCredentials([usernamePassword(usernameVariable: 'GIT_CRED_USER', passwordVariable: 'GIT_CRED_PASS', credentialsId: jenkinsGitCredentials)]) {
                sh "git push https://Demonslyr:${GIT_CRED_PASS}@github.com/${gitOpsRepo} ${gitOpsBranch}"
            }
        }
    }
}
