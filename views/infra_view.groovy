def default_jobs = "seed"

def view_list = [
  ["name": "Default", "description": "Frequently used jobs", "job_regex": "${default_jobs}"],
  ["name": "Squid", "description": "Ceph Squid Jobs", "job_regex": ".*squid.*"],
  ["name": "Tentacle", "description": "Ceph Tentacle Jobs", "job_regex": ".*tentacle.*"],
  ["name": "Executor", "description": "Test executor jobs", "job_regex": ".*executor.*"]
]

for (view_config in view_list){
    listView(view_config["name"]) {
        description(view_config["description"])
        columns {
            // Adds a column showing the status of the last build.
            status()
            // Adds a column showing a button for scheduling a build.
            buildButton()
            // Adds a column showing a button for opening the console output.
            lastBuildConsole()
            // Adds a column showing the item name.
            name()
            // Adds a column showing job's progress bar.
            progressBar()
            // Adds a column showing the last failed build.
            lastFailure()
            // Adds a column showing the last successful build.
            lastSuccess()
            // Adds a column showing the duration of the last build.
            lastDuration()
            // Adds a column showing a button for opening the item's configuration.
            configureProject()
            // Adds a column showing a button or an icon for disabling/enabling a project.
            disableProject(icon = true)
        }
        jobs {
            // If configured, the regular expression will be applied to all job names.
            regex (view_config["job_regex"])
        }
    }
}
