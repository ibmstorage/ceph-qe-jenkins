/**
 * Resolve the day-1 EC scheme Jenkins parameter.
 *
 * Supported EC_SCHEME values: false (default), 2+2, 4+2.
 * When a scheme is set, worker count is raised to k+m+1 if WORKER_COUNT is lower.
 * A higher explicit WORKER_COUNT is kept.
 *
 * Parameters:
 *   scheme      - EC_SCHEME value (default false)
 *   workerCount - requested WORKER_COUNT
 *
 * Returns a map: scheme, enabled, k, m, recommendedWorkers, workerCount, error
 */
def call(Map parameters = [:]) {
    def raw = (parameters.scheme ?: 'false').toString().trim().toLowerCase()
    def requested = (parameters.workerCount ?: 3) as Integer

    // recommendedWorkers = k + m + 1
    def table = [
        'false': [enabled: false, k: 2, m: 2, recommendedWorkers: 0],
        '2+2'  : [enabled: true,  k: 2, m: 2, recommendedWorkers: 5],
        '4+2'  : [enabled: true,  k: 4, m: 2, recommendedWorkers: 7],
    ]
    if (!table.containsKey(raw)) {
        def allowed = table.keySet().toList().sort()
        return [
            scheme            : raw,
            enabled           : false,
            k                 : 2,
            m                 : 2,
            recommendedWorkers: 0,
            workerCount       : requested,
            error             : "EC_SCHEME must be one of ${allowed}; got '${raw}'",
        ]
    }

    def spec = table[raw]
    def workers = spec.enabled ? Math.max(requested, spec.recommendedWorkers as Integer) : requested
    return [
        scheme            : raw,
        enabled           : spec.enabled,
        k                 : spec.k,
        m                 : spec.m,
        recommendedWorkers: spec.recommendedWorkers,
        workerCount       : workers,
        error             : null,
    ]
}
