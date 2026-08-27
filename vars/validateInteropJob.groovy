/**
 * Validate and resolve interop job parameters (ODF or FDF).
 *
 * Wraps validateOdfInteropJob; catalog resolution is product-aware inside
 * resolveOdfInteropParams / validateOdfInteropJob.
 *
 * Parameters:
 *   product - odf | fdf (default from params.INTEROP_PRODUCT or env)
 *   params, pullSecretFile, defaults, credentials - passed to validateOdfInteropJob
 */
def call(Map parameters) {
    def product = (parameters.product ?: parameters.params?.INTEROP_PRODUCT ?: env.INTEROP_PRODUCT ?: 'odf')
        .toString().trim().toLowerCase()

    if (!(product in ['odf', 'fdf'])) {
        product = env.JOB_NAME?.contains('fdf') ? 'fdf' : 'odf'
    }
    env.INTEROP_PRODUCT = product

    return validateOdfInteropJob(
        params: parameters.params,
        pullSecretFile: parameters.pullSecretFile,
        defaults: parameters.defaults,
        credentials: parameters.credentials,
        product: product,
    )
}
