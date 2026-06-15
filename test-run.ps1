$ErrorActionPreference = 'Continue'
Write-Output "Starting test execution..."
try {
    # Run the packaging script and capture all streams (*>)
    & .\inspecto\package.ps1 -NoBuild -NoUi *> test-output.log
    Write-Output "Execution finished."
} catch {
    Write-Output "Exception occurred: $_"
}
