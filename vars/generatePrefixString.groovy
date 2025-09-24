// Method to generate a random prefix for resources

import main.groovy.cephJobLib.jobConfigs


def call() {
    // Set prefix parameters
    def alphabet = (('A'..'Z')+('a'..'z')+('0'..'9')).join('')

    // Generate random string
    def ran = new Random()
    def str = (1..jobConfigs.PREFIX_LENGTH).collect { alphabet[ ran.nextInt( alphabet.length() ) ] }.join('')

    // Return the generated string
    return str
}
