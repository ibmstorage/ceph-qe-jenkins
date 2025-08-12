FROM docker.io/jenkins/jenkins:rhel-ubi9-jdk21

# Copy the required files
COPY --chown=jenkins:jenkins plugins.txt /usr/share/jenkins/ref/plugins.txt
COPY --chown=jenkins:jenkins casc.yaml /usr/share/jenkins/casc.yaml
COPY --chown=jenkins:jenkins jobs/seed.groovy /usr/share/jenkins/seed.groovy

USER jenkins

# Environment variables
ENV JAVA_OPTS="-Djenkins.install.runSetupWizard=false"
ENV CASC_JENKINS_CONFIG="/usr/share/jenkins/casc.yaml"
ENV JENKINS_JAVA_OPTIONS="-Xms512m -Xmx4g"

# Install plugins
RUN jenkins-plugin-cli -f /usr/share/jenkins/ref/plugins.txt --verbose --latest true

# Attach secrets as a volume to avoid leaking details to image
VOLUME ["/secrets"]
