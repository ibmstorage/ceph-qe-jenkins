FROM docker.io/jenkins/jenkins:lts-jdk21

# Build Arguments
ARG GHI_TOKEN
ARG SecretsManagerIAMKey

# Copy the required files
COPY --chown=jenkins:jenkins plugins.txt /usr/share/jenkins/ref/plugins.txt
COPY --chown=jenkins:jenkins casc.yaml /usr/share/jenkins/casc.yaml
COPY --chown=jenkins:jenkins jobs/seed.groovy /usr/share/jenkins/seed.groovy

USER jenkins

# Environment variables
ENV JAVA_OPTS="-Djenkins.install.runSetupWizard=false"
ENV CASC_JENKINS_CONFIG="/usr/share/jenkins/casc.yaml"

# Install plugins
RUN jenkins-plugin-cli -f /usr/share/jenkins/ref/plugins.txt --verbose

# CephCI specific variables
ENV GHI-PAT=${GHI_TOKEN}
ENV JENKINS_SSH_KEY="/var/secrets/jenkins_agent.key"
ENV SecretsManagerIAMKey=${SecretsManagerIAMKey}
