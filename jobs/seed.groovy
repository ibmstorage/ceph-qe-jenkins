package io.jenkins.plugins.casc

def job = freeStyleJob('seed') {
    displayName('Seed job for generating jobs and views.')

    label ("agents")

    wrappers {
        preBuildCleanup()
    }

    parameters {
        stringParam('JOBS_REPOSITORY', 'https://github.ibm.com/ceph-qe-ng/jenkins.git', 'Jobs Repository to clone')
        stringParam('JOBS_REPOSITORY_BRANCH', 'main', 'Name of branch from Jobs Repository to checkout')
        booleanParam('JOBS', true, 'Generate jobs')
        booleanParam('VIEWS', true, 'Generate views')
        stringParam('JOBS_PATH', 'jobs/*.groovy', 'Path to Job DSL definition files (default: jobs/*.groovy)')
        stringParam('VIEWS_PATH', 'views/*.groovy', 'Path to Views DLS definition files (default: views/*.groovy)')
    }

    scm {
        git {
            branch('${JOBS_REPOSITORY_BRANCH}')
            remote {
                name('origin')
                url('${JOBS_REPOSITORY}')
                credentials('ceph-qe-sa-pat')
            }
        }
    }

    steps {
        shell('echo "JOBS: ${JOBS}"')
        shell('echo "JOBS_PATH: ${JOBS_PATH}"')
        shell('echo "VIEWS: ${VIEWS}"')
        shell('echo "VIEWS_PATH: ${VIEWS_PATH}"')
        conditionalSteps {
            condition {
                booleanCondition('${JOBS}')
            }

            runner("Run")
            steps {
                shell('echo "Generating jobs."')
                dsl {
                    external('${JOBS_PATH}')
                    additionalClasspath(['src/main/groovy', ].join('\n'))
                    removeAction('IGNORE')
                }
            }
        }

        conditionalSteps {
            condition {
                booleanCondition('${VIEWS}')
            }

            runner("Run")

            steps {
                shell('echo "Generating views."')
                dsl {
                    external('${VIEWS_PATH}')
                    additionalClasspath(['src/main/groovy', ].join('\n'))
                    removeViewAction('IGNORE')
                }
            }
        }
    }
}

// This accepts changes in the script approval section of 'Manage Jenkins'.  You may or may not want this.
org.jenkinsci.plugins.scriptsecurity.scripts.ScriptApproval.get().with { approval ->
    approval.preapproveAll()
    approval.save()
}
