#
# LinuxGSM Empires Mod Dockerfile
#
# https://github.com/GameServerManagers/docker-gameserver
#

FROM docker.atriarch.systems/linuxgsm:ubuntu-2204
LABEL maintainer="Atriarch Systems <postmaster@mail.atriarch.systems>"
ARG SHORTNAME=em
ENV GAMESERVER=emserver

WORKDIR /app

## Auto install game server requirements
RUN depshortname=$(curl --connect-timeout 10 -s https://raw.githubusercontent.com/Demonslyr/LinuxGSM/master/lgsm/data/ubuntu-22.04.csv |awk -v shortname="em" -F, '$1==shortname {$1=""; print $0}') \
  && if [ -n "${depshortname}" ]; then \
  echo "**** Install ${depshortname} ****" \
  && apt-get update \
  && apt-get install -y ${depshortname} \
  && apt-get -y autoremove \
  && apt-get clean \
  && rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/*; \
  fi

HEALTHCHECK --interval=1m --timeout=1m --start-period=2m --retries=1 CMD /app/entrypoint-healthcheck.sh || exit 1

RUN date > /build-time.txt

ENTRYPOINT ["/bin/bash", "./entrypoint.sh"]
