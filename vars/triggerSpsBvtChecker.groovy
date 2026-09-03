/*
    Build Verification result validator job trigger.

    This job is implemented and hosted by the Ceph build team. It resides in
    the Secure Pipeline Services - bvt-result-verification. This job accepts
    a parameter BVT_RESULTS.

    To trigger this job, we require the toolchain id, IAM Token and ibm-tools
    implementation.

    ibm-tools works will with python3.11 and above.
*/

def call(Map parameters) {

    withCredentials ([
        usernamePassword(credentialsId: 'ceph-qe-sa-pat', usernameVariable: 'GHI_USER', passwordVariable: 'GHI_TOKEN'),
        string(credentialsId: 'sps-api-key', variable: 'CD_TEKTON_PIPELINE_APIKEY')
    ]) {
        // paramemters.release holds the value nightly, rc, etc
        // The parameter in SPS job that needs to be set is BUILD_TYPE
        // The below are coded in handshake with the build team.
        withEnv([
            "CD_TEKTON_PIPELINE_URL=https://api.us-south.devops.cloud.ibm.com/pipeline/v2",
            "CD_TEKTON_PIPELINE_AUTH_TYPE=iam",
            "WRKSPACE=${parameters.wrkspace}",
            "TEST_RESULTS=${parameters.results}",
            "PRODUCT=${parameters.product}",
            "VERSION=${parameters.cephVersion}",
            "CONTAINER_IMAGE=${parameters.containerImage}",
            "BUILD_TYPE=${parameters.release}"
        ]) {
            sh '''
                set -eo pipefail

                cd $(dirname "$WRKSPACE")

                git clone --depth 1 --branch main https://"$GHI_TOKEN"@github.ibm.com/ceph/ibm-tools.git
                pushd ibm-tools

                python3.12 -m venv it_venv
                it_venv/bin/python3 -m pip install --upgrade pip
                it_venv/bin/python3 -m pip install -r requirements.txt
                it_venv/bin/python3 -m pip install -e .

                it_venv/bin/python3 bin/sps trigger bvt-result-verification \
                    -p BVT_RESULT="$TEST_RESULTS" \
                    -p PRODUCT="$PRODUCT" \
                    -p VERSION="$VERSION" \
                    -p CONTAINER_IMAGE="$CONTAINER_IMAGE" \
                    -p BUILD_TYPE="$BUILD_TYPE"

                popd
                rm -rf ibm-tools
            '''
        }
    }
}
