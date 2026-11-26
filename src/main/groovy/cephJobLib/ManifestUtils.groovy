package main.groovy.cephJobLib

class ManifestUtils implements Serializable {

    @NonCPS
    static def deepCopySection(section) {
        if (section == null) {
            return null
        }
        if (section instanceof Map) {
            def result = new HashMap()
            section.each { key, value ->
                result.put(key.toString(), deepCopySection(value))
            }
            return result
        }
        if (section instanceof List) {
            return section.collect { deepCopySection(it) }
        }
        // Force GString/CharSequence to plain String at snapshot time so the
        // copy is Serializable and not lazily evaluated later in the pipeline.
        return (section instanceof CharSequence) ? section.toString() : section
    }

    /**
     * Deep-copies the nightly section and patches it with the values the job
     * actually used (after REPO / CONTAINER_IMAGE param overrides). This ensures
     * that stable promotion reflects what sanity validated, not raw manifest data.
     */
    @NonCPS
    static def buildPromotionSection(
            nightlySection,
            Map overrides = null
    ) {
        def section = deepCopySection(nightlySection)
        if (!section) {
            return null
        }

        if (overrides == null) {
            overrides = new HashMap()
        }

        def containerImage = overrides.containerImage?.toString()
        if (containerImage) {
            if (!section.images) {
                section.images = new HashMap()
            }
            section.images["ceph-base"] = containerImage
        }

        def rhsCephRepo = overrides.rhsCephRepo?.toString()
        def platform = overrides.platform?.toString()
        def repoDC = overrides.repoDC?.toString()
        if (rhsCephRepo && platform && repoDC) {
            if (!section.repositories) {
                section.repositories =  new HashMap()
            }
            if (!section.repositories[repoDC]) {
                section.repositories[repoDC] =  new HashMap()
            }
            section.repositories[repoDC][platform] = rhsCephRepo
        }

        return section
    }
}
