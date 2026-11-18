/**
 * Write ceph-qe-zypper configs/credentials.yaml from Jenkins secrets.
 *
 * Must be called inside dir('ceph-qe-zypper') (or parameters.checkoutDir).
 *
 * Uses:
 *   - env.IBM_CLOUD_API_KEY (+ optional COS HMAC env vars)
 *   - SSH private key: prefer agent ~/.ssh/id_ed25519 (IBM Cloud ceph-qe-sa),
 *     else ~/.ssh/id_rsa, else Jenkins vmLoginAccessKey
 *   - RHSM: Jenkins usernamePassword credential rh-cdn-access (Red Hat CDN)
 *   - optional params.SSH_PUBLIC_KEY / env for ssh_authorized_keys
 */
def call(Map parameters = [:]) {
    def checkoutDir = (parameters.checkoutDir ?: 'ceph-qe-zypper').toString()

    withCredentials([
        usernamePassword(
            credentialsId: 'rh-cdn-access',
            usernameVariable: 'RH_CDN_USER',
            passwordVariable: 'RH_CDN_PASS',
        )
    ]) {
        dir(checkoutDir) {
            sh 'mkdir -p configs .ci-ssh'

            // Lab IBM Cloud key is ed25519 on agents; do not prefer id_rsa over it.
            def agentEd25519 = '/home/jenkins/.ssh/id_ed25519'
            def agentRsa = '/home/jenkins/.ssh/id_rsa'
            def sshKeyRel
            def sshKeyAbs
            if (fileExists(agentEd25519)) {
                sshKeyRel = '.ci-ssh/id_ed25519'
                sshKeyAbs = "${env.WORKSPACE}/${checkoutDir}/${sshKeyRel}"
                sh """
                    cp '${agentEd25519}' '${sshKeyRel}' && chmod 600 '${sshKeyRel}'
                    if [ -f '${agentEd25519}.pub' ]; then
                      cp '${agentEd25519}.pub' '${sshKeyRel}.pub' && chmod 644 '${sshKeyRel}.pub'
                    fi
                """
                echo "SSH private key: copied agent ${agentEd25519} → ${sshKeyAbs}"
            } else if (fileExists(agentRsa)) {
                sshKeyRel = '.ci-ssh/id_rsa'
                sshKeyAbs = "${env.WORKSPACE}/${checkoutDir}/${sshKeyRel}"
                sh """
                    cp '${agentRsa}' '${sshKeyRel}' && chmod 600 '${sshKeyRel}'
                    if [ -f '${agentRsa}.pub' ]; then
                      cp '${agentRsa}.pub' '${sshKeyRel}.pub' && chmod 644 '${sshKeyRel}.pub'
                    fi
                """
                echo "SSH private key: copied agent ${agentRsa} → ${sshKeyAbs}"
            } else {
                sshKeyRel = '.ci-ssh/id_rsa'
                sshKeyAbs = "${env.WORKSPACE}/${checkoutDir}/${sshKeyRel}"
                withCredentials([
                    sshUserPrivateKey(credentialsId: 'vmLoginAccessKey', keyFileVariable: 'SCALE_SSH_KEY')
                ]) {
                    sh "cp \"\${SCALE_SSH_KEY}\" '${sshKeyRel}' && chmod 600 '${sshKeyRel}'"
                }
                echo "SSH private key: copied Jenkins credential vmLoginAccessKey → ${sshKeyAbs}"
            }

            def ibmCloud = [api_key: env.IBM_CLOUD_API_KEY]
            if (env.IBM_COS_HMAC_ACCESS_KEY?.trim()) {
                ibmCloud.cos_hmac_access_key_id = env.IBM_COS_HMAC_ACCESS_KEY
            }
            if (env.IBM_COS_HMAC_SECRET_KEY?.trim()) {
                ibmCloud.cos_hmac_secret_access_key = env.IBM_COS_HMAC_SECRET_KEY
            }

            def data = [
                accounts: [ibm_qe: [ibmcloud: ibmCloud]],
                ssh_private_key: sshKeyAbs,
                rhel_subscription: [
                    username: env.RH_CDN_USER,
                    password: env.RH_CDN_PASS,
                ],
            ]

            // Optional operator .pub paths (param and/or the key we just staged)
            def authorized = []
            def pubKey = (parameters.sshPublicKey ?: params.SSH_PUBLIC_KEY ?: '').toString().trim()
            if (pubKey) {
                authorized << pubKey
            }
            def stagedPubWs = "${checkoutDir}/${sshKeyRel}.pub"
            if (fileExists(stagedPubWs)) {
                authorized << "${env.WORKSPACE}/${stagedPubWs}"
            }
            if (authorized) {
                data.ssh_authorized_keys = authorized.unique()
            }

            writeYaml(file: 'configs/credentials.yaml', data: data, overwrite: true)
            sh 'chmod 600 configs/credentials.yaml'
            echo 'Wrote configs/credentials.yaml (IBM + SSH + rhel_subscription from rh-cdn-access)'
        }
    }
}
