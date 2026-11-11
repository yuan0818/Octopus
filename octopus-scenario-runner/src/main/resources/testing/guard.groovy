import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import java.net.URLEncoder

// Trusted compiler template. Parameters are base64 JSON data, never interpolated Groovy.
def typeMatches = { value, type ->
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
    def spec = new JsonSlurper().parseText(new String(Base64.decoder.decode(Parameters), 'UTF-8'))
    def context = vars.getObject('oct.context')
    if(context == null) {
        def contextFile = props.getProperty('octopus.contextFile')
        if(!contextFile) throw new IllegalStateException('Initial context file missing')
        context = new JsonSlurper().parse(new File(contextFile), 'UTF-8')
        if(!(context instanceof Map)) throw new IllegalStateException('Initial context must be an object')
        spec.initialContext.each { key, declaration ->
            if(!context.containsKey(key) || !typeMatches(context[key], declaration.type))
                throw new IllegalStateException('Initial context missing or wrong type: ' + key)
        }
        context = new LinkedHashMap(context)
        vars.putObject('oct.context', context)
    }
    def step = spec.step
    step.states.each { constraint ->
        def model = spec.stateModels.find { it.modelId == constraint.modelId }
        def rule = model.transitions.find { it.ruleId == constraint.ruleId }
        if(!context.containsKey(constraint.entityContext) || !context.containsKey(constraint.observedContext))
            throw new IllegalStateException('State observation or entity is missing')
        if(context[constraint.entityContext] != context[constraint.observedEntityContext])
            throw new IllegalStateException('State observation belongs to another entity')
        def observed = context[constraint.observedContext]
        def negative = spec.negativeRule == constraint.modelId + '/' + constraint.ruleId
        if(!model.states.contains(observed) || (negative ? observed == rule.from : observed != rule.from))
            throw new IllegalStateException('State precondition failed: ' + constraint.ruleId)
    }
    def resolved = [:]
    step.inputs.each { name, binding ->
        def value
        switch(binding.source) {
            case 'literal': value = binding.value; break
            case 'context':
                if(!context.containsKey(binding.value)) throw new IllegalStateException('Context unavailable: ' + binding.value)
                value = context[binding.value]; break
            case 'environment':
                def file = props.getProperty('octopus.environmentFile')
                def environments = file ? new JsonSlurper().parse(new File(file), 'UTF-8') : [:]
                if(!environments[step.id]?.inputs?.containsKey(name)) throw new IllegalStateException('Environment input unavailable')
                value = environments[step.id].inputs[name]
                break
            case 'secret':
                def envName = spec.secretEnvironmentNames[binding.value]
                if(!envName) throw new IllegalStateException('Secret reference not configured')
                value = System.getenv(envName)
                if(value == null) throw new IllegalStateException('Secret environment variable unavailable')
                break
            default: throw new IllegalStateException('Unsupported binding')
        }
        if(!typeMatches(value, spec.operation.inputs[name].type)) throw new IllegalStateException('Input type mismatch: ' + name)
        resolved[name] = value
    }
    def encode = { value -> URLEncoder.encode(String.valueOf(value), 'UTF-8').replace('+', '%20') }
    def path = spec.basePath + spec.operation.path
    def query = []
    def queryValues = [:]
    def headerValues = [:]
    def body = [:]
    boolean hasBody = false
    spec.operation.inputs.each { name, port ->
        if(!resolved.containsKey(name)) {
            if(port.required && !(spec.preparedInputs ?: []).contains(name)) throw new IllegalStateException('Required input missing: ' + name)
            if(port.location == 'header') vars.put('oct.header.' + step.id + '.' + name, '')
        } else {
            def value = resolved[name]
            switch(port.location) {
                case 'path': path = path.replace('{' + port.name + '}', encode(value)); break
                case 'query': query.add(encode(port.name) + '=' + encode(value)); queryValues[port.name]=String.valueOf(value); break
                case 'header':
                    def header = (port.prefix ?: '') + String.valueOf(value)
                    if(header.contains('\r') || header.contains('\n')) throw new IllegalStateException('Invalid header value')
                    vars.put('oct.header.' + step.id + '.' + name, header); headerValues[port.name]=header; break
                case 'body':
                    hasBody = true
                    if(port.name == '$') body = value
                    else body[port.name] = value
                    break
                default: throw new IllegalStateException('Unsupported input placement')
            }
        }
    }
    if(path.contains('{') || path.contains('}')) throw new IllegalStateException('Unresolved path parameter')
    vars.put('oct.path.' + step.id, path + (query ? '?' + query.join('&') : ''))
    vars.put('oct.body.' + step.id, hasBody ? JsonOutput.toJson(body) : '')
    if(step.preparation) {
        vars.putObject('oct.preparation.inputs.' + step.id, resolved)
        vars.putObject('oct.preparation.request.' + step.id, [body:body, query:queryValues, headers:headerValues])
        vars.putObject('oct.preparation.results.' + step.id, [:])
    }
    if(step.poll) {
        // Phase 2.1：初始化轮询状态（While 条件、轮次、起始时间、下一轮等待）
        vars.put('oct.poll.' + step.id + '.continue', 'true')
        vars.put('oct.poll.' + step.id + '.attempt', '0')
        vars.put('oct.poll.' + step.id + '.start', String.valueOf(System.currentTimeMillis()))
        vars.put('oct.poll.' + step.id + '.delay', '0')
    }
    SampleResult.setResponseCode('200')
    SampleResult.setResponseMessage('Context and state preconditions satisfied')
    SampleResult.setSuccessful(true)
} catch(Exception failure) {
    SampleResult.setResponseCode('CONTEXT_INVALID')
    SampleResult.setResponseMessage('Context or state precondition failed')
    SampleResult.setSuccessful(false)
}

if (!SampleResult.isSuccessful() && vars.getObject('oct.flowRunState') != null) {
    synchronized(props) {
        if(vars.get('oct.isCleanup') != 'true') vars.getObject('oct.flowRunState').failed = true
    }
}
