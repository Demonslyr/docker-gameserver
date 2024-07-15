properties([pipelineTriggers([githubPush()])])
node {
    jenkinsGitCredentials = 'jenkinsGitHubSvc'
    git url: 'https://github.com/Demonslyr/docker-gameserver.git', branch: 'atriarch', credentialsId: jenkinsGitCredentials

    stage('setup') {
        checkout scm
        currentBuild.description = "${Branch}"
        dockerRepo = "${DockerRepo}"
        dockerCredId = "${DockerCredentials}"
        imageNamePrefix= "${ImageNamePrefix}"
        gitOpsRepo = "${GitOpsRepo}"
        gitOpsBranch = "${GitOpsBranch}"
        pathToDeploymentYaml = "${PathToDeploymentYaml}"
        pathToDockerfileDirectory = "${pathToDockerfileDirectory}"
    }

    stage('build and push') {
        def dockerfiles = sh(returnStdout: true, script: "ls ${pathToDockerfileDirectory}").trim().split('\n')

        for (dockerfile in dockerfiles) {
            def appName = dockerfile.replace('dockerfile.', '')
            def fullImageName = "${dockerRepo}/${imageNamePrefix}${appName}:v1.0.${BUILD_NUMBER}"

            withCredentials([usernamePassword(usernameVariable: 'DOCKER_USER', passwordVariable: 'DOCKER_PASS', credentialsId: dockerCredId)]) {
                def loginout = sh(returnStdout: true, script: "echo ${DOCKER_PASS} | docker login ${dockerRepo} --username ${DOCKER_USER} --password-stdin")
                println loginout

                def buildout = sh(returnStdout: true, script: "docker build -t ${appName} -f dockerfiles/${dockerfile} .")
                println buildout

                def tagout = sh(returnStdout: true, script: "docker tag ${appName} ${fullImageName}")
                println tagout

                def pushout = sh(returnStdout: true, script: "docker push ${fullImageName}")
                println pushout
            }
        }
    }

    stage('updateGitOpsRepo') {
        dir('GitOps') { // Clone the repo in a new workspace to avoid conflicts
            git url: "https://github.com/${gitOpsRepo}", branch: gitOpsBranch, credentialsId: jenkinsGitCredentials

            def dockerfiles = sh(returnStdout: true, script: 'ls ../dockerfiles').trim().split('\n')

            for (dockerfile in dockerfiles) {
                def appName = dockerfile.replace('dockerfile.', '')
                def fullImageName = "${dockerRepo}/${appName}:v1.0.${BUILD_NUMBER}"

                sh """
                find /AtriarchGameHosting/Servers -type f -name '*.yaml' | while read file; do
                    python3 -c "
import yaml
with open('\$file', 'r') as f:
    docs = yaml.safe_load_all(f)
    updated_docs = []
    for doc in docs:
        if doc['kind'] == 'Deployment' and doc['metadata']['name'] == '${appName}':
            for container in doc['spec']['template']['spec']['containers']:
                if 'image' in container:
                    container['image'] = '${fullImageName}'
        updated_docs.append(doc)
with open('\$file', 'w') as f:
    yaml.safe_dump_all(updated_docs, f, explicit_start=True)
"
                done
                """

                sh 'git add /AtriarchGameHosting/Servers/*.yaml'
            }
            sh "git commit -m \"Update server versions to ${BUILD_NUMBER}\""

            withCredentials([usernamePassword(usernameVariable: 'GIT_CRED_USER', passwordVariable: 'GIT_CRED_PASS', credentialsId: jenkinsGitCredentials)]) {
                sh "git push https://Demonslyr:${GIT_CRED_PASS}@github.com/${gitOpsRepo} ${gitOpsBranch}"
            }
        }
    }
}
