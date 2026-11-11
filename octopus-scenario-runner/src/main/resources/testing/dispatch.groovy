import groovy.json.JsonSlurper
import groovy.json.JsonOutput

// JMeter serializes the thread groups. Only a completed group publishes to the next one.
def spec = new JsonSlurper().parseText(new String(Base64.decoder.decode(Parameters), 'UTF-8'))
def copy = { value -> new JsonSlurper().parseText(JsonOutput.toJson(value)) }
def matches = { value, type ->
    switch(type) {
        case 'string': return value instanceof String
        case 'boolean': return value instanceof Boolean
        case 'number': return value instanceof Number
        case 'integer': return value instanceof Number && value.toBigDecimal().stripTrailingZeros().scale() <= 0
        case 'object': return value instanceof Map
        case 'array': return value instanceof List
        default: return false
    }
}
try {
    synchronized(props) {
        def state = props.get('oct.ordered.state')
        if (state == null) {
            if (spec.groupIndex != 0) throw new IllegalStateException('Missing preceding group')
            def file = props.getProperty('octopus.contextFile')
            def initial = file ? new JsonSlurper().parse(new File(file), 'UTF-8') : null
            if (!(initial instanceof Map)) throw new IllegalStateException('Initial context unavailable')
            spec.initialContext.each { key, declaration ->
                if (!initial.containsKey(key) || !matches(initial[key], declaration.type))
                    throw new IllegalStateException('Initial context type mismatch')
            }
            state = [context:copy(initial), completed:-1, failed:false, businessFailed:false, verifiedOutputs:[:], groups:[:]]
            props.put('oct.ordered.state', state)
        }
        vars.putObject('oct.flowRunState', state)
        vars.put('oct.isCleanup',spec.cleanup ? 'true' : 'false')
        def group = state.groups[spec.group.id]
        if(spec.cleanup && group == null) {
            def terminal = state.businessFailed ? 'FAIL' : state.failed ? 'INCONCLUSIVE' : 'PASS'
            if(!spec.cleanup.when.contains(terminal) || !state.verifiedOutputs.containsKey(spec.cleanup.resourceKey)) {
                SampleResult.setResponseCode(!spec.cleanup.when.contains(terminal) ? 'CLEANUP_NOT_APPLICABLE' : 'CLEANUP_NOT_RUN')
                SampleResult.setSuccessful(true)
                SampleResult.setStopThread(true)
                return
            }
            def known = new LinkedHashMap(state.context)
            known.putAll(state.verifiedOutputs)
            group = [next:0,done:0,index:spec.groupIndex,entry:copy(known),working:copy(known),outputs:[:]]
            state.groups[spec.group.id]=group
        }
        if (!spec.cleanup && (state.failed || (group == null && state.completed != spec.groupIndex - 1))) {
            SampleResult.setResponseCode('FLOW_BLOCKED')
            SampleResult.setSuccessful(false)
            SampleResult.setStopThread(true)
            return
        }
        if (group == null) {
            // Check every external input before the first request in the group is dispatched.
            def localOutputs = spec.calls.collectMany { it.outputs*.key }.toSet()
            spec.calls.each { call ->
                call.inputs.each { key, binding ->
                    if (binding.source == 'context' && !localOutputs.contains(binding.value)) {
                        if (!state.context.containsKey(binding.value) || !matches(state.context[binding.value], spec.operations[call.operationRef].inputs[key].type))
                            throw new IllegalStateException('Group input unavailable')
                    }
                    if (binding.source == 'environment') {
                        def file = props.getProperty('octopus.environmentFile')
                        def environments = file ? new JsonSlurper().parse(new File(file), 'UTF-8') : [:]
                        if(!environments[call.id]?.inputs?.containsKey(key) || !matches(environments[call.id].inputs[key],spec.operations[call.operationRef].inputs[key].type))
                            throw new IllegalStateException('Group environment input unavailable')
                    }
                    if (binding.source == 'secret') {
                        def name = spec.secretEnvironmentNames[binding.value]
                        if (!name || System.getenv(name) == null) throw new IllegalStateException('Secret unavailable')
                    }
                }
            }
            group = [next:0, done:0, index:spec.groupIndex, entry:copy(state.context), working:copy(state.context), outputs:[:]]
            state.groups[spec.group.id] = group
        }
        if (group.next >= spec.group.callIds.size()) {
            SampleResult.setSuccessful(true)
            SampleResult.setStopThread(true)
            SampleResult.setIgnore()
            return
        }
        int index = group.next++
        vars.put('oct.call.index', String.valueOf(index))
        vars.putObject('oct.flowGroupState', group)
        vars.putObject('oct.context', copy(spec.group.mode == 'PARALLEL' ? group.entry : group.working))
        vars.putObject('oct.observationMarkers', [:])
        SampleResult.setSuccessful(true)
        SampleResult.setIgnore()
    }
} catch (Exception ignored) {
    synchronized(props) {
        def state = props.get('oct.ordered.state')
        if (state != null && !spec.cleanup) state.failed = true
    }
    SampleResult.setResponseCode('FLOW_INPUT_ERROR')
    SampleResult.setResponseMessage('Step entry preflight failed')
    SampleResult.setSuccessful(false)
    SampleResult.setStopThread(true)
}
