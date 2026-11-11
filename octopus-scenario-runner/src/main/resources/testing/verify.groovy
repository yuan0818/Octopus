import groovy.json.JsonSlurper
import groovy.json.JsonOutput

// Only this trusted template executes. The LLM's output remains JSON data.
try {
    def spec = new JsonSlurper().parseText(new String(Base64.decoder.decode(Parameters), 'UTF-8'))
    def runtime = Class.forName('io.yuan.octopus.verification.VerificationRuntime')
    def markers = vars.getObject('oct.observationMarkers')
    if (markers == null) { markers = [:]; vars.putObject('oct.observationMarkers', markers) }
    def plan = JsonOutput.toJson(spec.plan)
    def file = props.getProperty('octopus.connectionsFile')
    def result = spec.capture ? runtime.capture(plan, file, spec.connectionKey, markers, spec.markerKey)
        : runtime.verify(plan, file, spec.connectionKey, vars.getObject('oct.context'), markers, spec.markerKey)
    SampleResult.setSuccessful(result.success())
    SampleResult.setResponseCode(result.code())
    SampleResult.setResponseMessage(result.message())
    // No query rows, message payloads, endpoints or credentials are copied into samples or logs.
    SampleResult.setResponseData('', 'UTF-8')
} catch (Exception | LinkageError ignored) {
    SampleResult.setSuccessful(false)
    SampleResult.setResponseCode('VERIFY_ERROR')
    SampleResult.setResponseMessage('Datastore verification extension unavailable')
}

if (!SampleResult.isSuccessful() && vars.getObject('oct.flowRunState') != null) {
    synchronized(props) {
        if(vars.get('oct.isCleanup') != 'true') vars.getObject('oct.flowRunState').failed = true
    }
}

if(SampleResult.getResponseCode()=='VERIFY_MISMATCH' && vars.getObject('oct.flowRunState') != null && vars.get('oct.isCleanup') != 'true') { synchronized(props) {vars.getObject('oct.flowRunState').businessFailed=true} }
