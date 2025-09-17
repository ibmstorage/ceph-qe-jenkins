/*
    Synchronize RHEL OS RPMS from CDN repository.

    Salient points
        - This is a timed workflow
        - Keeps the specified mirror repository up-to-date
*/
freeStyleJob("sync-rhel-rpms") {
    displayName("Infra - Synchronize mirror repository")

    label("infra")

    
}