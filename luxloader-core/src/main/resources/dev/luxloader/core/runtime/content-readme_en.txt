LuxLoader content directory
===========================

This directory sits beside mods/ and contains rendering pipelines, not the loader mod.

pipelines/      Install pipeline plugins here:
                  foo.jar       a packaged plugin
                  foo.zip       equivalent ZIP archive
                  my-pipeline/  one or more JARs plus shader sources or other files
                                for development without repackaging every shader edit
shader-cache/   Compiled shader cache; deleting it requires recompilation on next use.
reports/        Diagnostic reports to include when reporting a problem.

Configuration remains in config/luxloader/luxloader.json.

If a plugin does not activate:
  1. Check META-INF/services/dev.luxloader.api.plugin.PipelinePlugin inside its archive.
  2. Inspect plugin discovery and skipped-plugin messages in the log.
  3. Read Registered pipelines in the diagnostic report for unmet requirements.

See README.md in the LuxLoader and plugin repositories for build and installation instructions.
