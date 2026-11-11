import groovy.json.JsonSlurper
import groovy.json.JsonOutput

def spec = new JsonSlurper().parseText(new String(Base64.decoder.decode(Parameters), 'UTF-8'))
def step = spec.step
step.assertions = step.assertions.findAll { it.kind != 'DATASTORE_VERIFY' }
def errors = []
def schemaDetails = []
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
    def document = null
    if(step.outputs || step.states || step.assertions.any { it.kind != 'STATUS_EQUALS' }) {
        document = new JsonSlurper().parseText(prev.getResponseDataAsString())
    }
    def select = { path ->
        def value = document
        // LLM 补全遵循 "回应 JSON 位于 $.body" 的约定（FlowFillValidator），人工编辑使用根路径 $.x。
        // 两种写法都要能执行：$.body 前缀视为回应体本身；但若回应体根上真的有 body 字段，按字面路径解析（兼容旧流程）。
        if(path ==~ /\$\.body(?:[.\[].*)?/ && !(document instanceof Map && document.containsKey('body'))) {
            path = '$' + path.substring('$.body'.length())
        }
        def matcher = (path.substring(1) =~ /\.([A-Za-z_][A-Za-z0-9_]*)|\[([0-9]+)\]/)
        while(matcher.find()) {
            if(matcher.group(1) != null) {
                def key = matcher.group(1)
                if(!(value instanceof Map) || !value.containsKey(key)) throw new IllegalStateException('Missing response field')
                value = value[key]
            } else {
                int index = Integer.parseInt(matcher.group(2))
                if(!(value instanceof List) || index >= value.size()) throw new IllegalStateException('Missing response index')
                value = value[index]
            }
        }
        return value
    }
    step.assertions.each { assertion ->
        try {
            switch(assertion.kind) {
                case 'STATUS_EQUALS':
                    if(prev.getResponseCode() != String.valueOf(assertion.expected.intValue())) errors.add(assertion.id)
                    break
                case 'JSON_EQUALS':
                    if(select(assertion.path) != assertion.expected) errors.add(assertion.id)
                    break
                case 'JSON_EXISTS': select(assertion.path); break
                case 'SCHEMA_CONFORMS':
                    // 校验器位于 octopus-jmeter-ext（执行前已检查扩展版本）；违规描述只含路径与原因，不含回应值
                    def checker = Class.forName('io.yuan.octopus.verification.JsonSchemaSubset')
                    def problems = checker.violations(spec.responseSchemas[assertion.id], select(assertion.path))
                    if(problems) {
                        errors.add(assertion.id)
                        schemaDetails.addAll(problems.take(5).collect { assertion.id + ' ' + it })
                    }
                    break
                default: errors.add(assertion.id)
            }
        } catch(Exception ignored) { errors.add(assertion.id) }
    }
    def context = vars.getObject('oct.context')
    def pending = [:]
    step.outputs.each { output ->
        try {
            def value = select(output.path)
            if(!typeMatches(value, output.type) || context.containsKey(output.key)) errors.add('output:' + output.key)
            else pending[output.key] = value
        } catch(Exception ignored) { errors.add('output:' + output.key) }
    }
    step.states.each { constraint ->
        try {
            def rule = spec.stateModels.find { it.modelId == constraint.modelId }.transitions.find { it.ruleId == constraint.ruleId }
            def negative = spec.negativeRule == constraint.modelId + '/' + constraint.ruleId
            def expected = negative ? context[constraint.observedContext] : rule.to
            if(select(constraint.afterEntityPath) != context[constraint.entityContext] || select(constraint.afterPath) != expected) errors.add('state:' + constraint.ruleId)
        } catch(Exception ignored) { errors.add('state:' + constraint.ruleId) }
    }
    if(!errors) context.putAll(pending)
} catch(Exception ignored) { errors.add('response_decode_or_context') }

// Phase 2.1：轮询。未通过且未到最后一轮时，本轮记为 POLL_RETRY（不提交上下文、不触发失败停止），等待下一轮。
boolean pollRetry = false
if(step.poll) {
    def key = 'oct.poll.' + step.id
    int attempt = Integer.parseInt(vars.get(key + '.attempt') ?: '0') + 1
    vars.put(key + '.attempt', String.valueOf(attempt))
    long interval = step.poll.intervalMillis.longValue()
    long timeout = step.poll.timeoutMillis.longValue()
    long started = Long.parseLong(vars.get(key + '.start') ?: String.valueOf(System.currentTimeMillis()))
    long maxAttempts = timeout.intdiv(interval) + 1
    // 最多 timeout/interval+1 轮；若请求本身很慢导致累计耗时已超过 timeout，也立即结束
    boolean last = attempt >= maxAttempts || System.currentTimeMillis() - started >= timeout
    if(errors && !last) {
        pollRetry = true
        vars.put(key + '.continue', 'true')
        vars.put(key + '.delay', String.valueOf(interval))
    } else {
        vars.put(key + '.continue', 'false')
        if(errors) errors.add('poll_timeout_after_' + attempt + '_attempts')
    }
}
// 用例生成探测（设计 8.3）：仅当 -Joctopus.probeEvidenceFile 存在时，写入断言路径上的实际值与输出值摘要。
// 正式运行不设置该属性，行为不变。文件位于 rwx------ 的运行目录，服务端脱敏后立即删除。
def probeEvidence = props.getProperty('octopus.probeEvidenceFile')
if(probeEvidence && !pollRetry) {
    try {
        def normalize = { v ->
            if(v == null) return 'null'
            if(v instanceof String) return v
            if(v instanceof Number) return new BigDecimal(v.toString()).stripTrailingZeros().toPlainString()
            if(v instanceof Boolean) return v.toString()
            return JsonOutput.toJson(v)
        }
        def sha = { v -> java.security.MessageDigest.getInstance('SHA-256').digest(normalize(v).getBytes('UTF-8')).encodeHex().toString() }
        def doc = null
        try { doc = new JsonSlurper().parseText(prev.getResponseDataAsString()) } catch(Exception ignored) { }
        def pick = { path ->
            def value = doc
            if(path ==~ /\$\.body(?:[.\[].*)?/ && !(doc instanceof Map && doc.containsKey('body'))) path = '$' + path.substring('$.body'.length())
            def m = (path.substring(1) =~ /\.([A-Za-z_][A-Za-z0-9_]*)|\[([0-9]+)\]/)
            while(m.find()) {
                if(m.group(1) != null) { if(!(value instanceof Map) || !value.containsKey(m.group(1))) return ['missing': true]; value = value[m.group(1)] }
                else { int i = Integer.parseInt(m.group(2)); if(!(value instanceof List) || i >= value.size()) return ['missing': true]; value = value[i] }
            }
            return ['value': value]
        }
        def rows = step.assertions.collect { a ->
            def row = ['id': a.id, 'path': a.path]
            if(a.kind == 'STATUS_EQUALS') row.actual = prev.getResponseCode()
            else if(a.path != null && doc != null) {
                def got = pick(a.path)
                if(got.missing) row.missing = true
                else { def text = got.value instanceof String ? got.value : JsonOutput.toJson(got.value); row.actual = text.length() > 2048 ? text.substring(0, 2048) : text }
            }
            row
        }
        def outs = step.outputs.collect { o ->
            def got = doc != null && o.path != null ? pick(o.path) : ['missing': true]
            got.missing ? ['key': o.key, 'missing': true, 'sensitive': o.sensitive] : ['key': o.key, 'sha256': sha(got.value), 'sensitive': o.sensitive]
        }
        def line = JsonOutput.toJson(['callId': step.id, 'status': prev.getResponseCode(), 'assertions': rows, 'outputs': outs])
        synchronized(probeEvidence.intern()) {
            new File(probeEvidence).withWriterAppend('UTF-8') { it.write(line + '\n') }
        }
    } catch(Exception ignored) { }
}
if(pollRetry) {
    AssertionResult.setFailure(false)
    prev.setResponseMessage('POLL_RETRY')
    prev.setSuccessful(true)
    return
}
if(errors) {
    AssertionResult.setFailure(true)
    AssertionResult.setFailureMessage('Business verification failed: ' + errors.unique().join(', ')
        + (schemaDetails ? ' | schema: ' + schemaDetails.join('; ') : ''))
    prev.setSuccessful(false)
} else {
    // A deliberately expected 4xx/5xx is successful only after every business oracle passes.
    AssertionResult.setFailure(false)
    prev.setSuccessful(true)
}

if (errors && vars.getObject('oct.flowRunState') != null) {
    synchronized(props) {
        if(vars.get('oct.isCleanup') != 'true') vars.getObject('oct.flowRunState').failed = true
    }
}

if(errors && vars.getObject('oct.flowRunState') != null && vars.get('oct.isCleanup') != 'true' && prev.getResponseCode() ==~ /[1-5][0-9]{2}/) { synchronized(props) {vars.getObject('oct.flowRunState').businessFailed=true} }
