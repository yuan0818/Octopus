import groovy.json.JsonSlurper
import groovy.json.JsonOutput

def spec = new JsonSlurper().parseText(new String(Base64.decoder.decode(Parameters), 'UTF-8'))
try {
    synchronized(props) {
        def state = vars.getObject('oct.flowRunState')
        def group = vars.getObject('oct.flowGroupState')
        def context = vars.getObject('oct.context')
        // Each call has its own context. Only its declared outputs cross the barrier.
        spec.outputs.each { output ->
            if (!context.containsKey(output.key) || group.outputs.containsKey(output.key)) throw new IllegalStateException('Output unavailable or duplicated')
            group.outputs[output.key] = new JsonSlurper().parseText(JsonOutput.toJson(context[output.key]))
        }
        if(spec.cleanup) {
            SampleResult.setResponseCode('200');SampleResult.setSuccessful(true);return
        }
        state.verifiedOutputs.putAll(group.outputs)
        group.done++
        if (spec.group.mode == 'SERIAL') group.working.putAll(group.outputs)
        if (group.done == spec.group.callIds.size() && !state.failed) {
            def combined = new LinkedHashMap(group.entry)
            combined.putAll(group.outputs)
            for (def check : spec.group.assertions) {
                if (!combined.containsKey(check.leftKey) || !combined.containsKey(check.rightKey) || combined[check.leftKey] != combined[check.rightKey]) {
                    state.failed = true
                    state.businessFailed = true
                    SampleResult.setResponseCode('FLOW_ASSERTION_FAILED')
                    SampleResult.setResponseMessage('Step result comparison failed: ' + check.id)
                    SampleResult.setSuccessful(false)
                    return
                }
            }
            state.context.putAll(group.outputs)
            state.completed = group.index
        }
        SampleResult.setResponseCode('200')
        SampleResult.setSuccessful(true)
    }
} catch (Exception ignored) {
    if (!spec.cleanup) synchronized(props) { vars.getObject('oct.flowRunState').failed = true }
    SampleResult.setResponseCode('FLOW_ERROR')
    SampleResult.setSuccessful(false)
}
