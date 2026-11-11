import groovy.json.JsonSlurper
import com.google.gson.Gson
import io.yuan.octopus.preparation.PreparationPlan
import io.yuan.octopus.preparation.PreparationRuntime

// Fixed compiler template. Only typed data reaches the extension; never evaluate model text.
try {
    def spec = new JsonSlurper().parseText(new String(Base64.decoder.decode(Parameters), 'UTF-8'))
    def id = spec.step.id
    def plan = new Gson().fromJson(groovy.json.JsonOutput.toJson(spec.step.preparation), PreparationPlan.class)
    def context = vars.getObject('oct.context')
    def inputs = vars.getObject('oct.preparation.inputs.' + id)
    def request = vars.getObject('oct.preparation.request.' + id)
    def results = vars.getObject('oct.preparation.results.' + id)
    def secrets = { key ->
        def env = spec.secretEnvironmentNames[key]
        if(!env || !(env ==~ /[A-Za-z_][A-Za-z0-9_]*/)) throw new PreparationRuntime.Failure('PREPARE_CREDENTIAL_MISSING')
        return System.getenv(env)
    } as java.util.function.Function
    def resolve = { v -> PreparationRuntime.resolve(v, context, inputs, request, results, secrets) }
    int index = spec.actionIndex.intValue()
    if(index < plan.tools().size()) {
        def call = plan.tools()[index]
        def args = call.inputs().collectEntries { key, value -> [(key):resolve(value)] }
        def output = PreparationRuntime.execute(call.tool(), call.version(), args)
        output.each { key, value -> results[call.id() + '.' + key] = value }
    } else {
        def headers = plan.headers().collectEntries { key, value -> [(key):resolve(value)] }
        headers.each { key,value ->
            if(!(value instanceof String) || value.contains('\r') || value.contains('\n')) throw new PreparationRuntime.Failure('PREPARE_HEADER_INVALID')
        }
        // Publish the final request only after every action has succeeded.
        if(plan.body()!=null) {
            def body=resolve(plan.body())
            if(!(body instanceof Map)) throw new PreparationRuntime.Failure('PREPARE_INPUT_INVALID')
            vars.put('oct.body.'+id,PreparationRuntime.wireJson(body))
        }
        headers.each { key,value -> vars.put('oct.preparedheader.'+id+'.'+key,value) }
        vars.remove('oct.preparation.inputs.'+id)
        vars.remove('oct.preparation.request.'+id)
        vars.remove('oct.preparation.results.'+id)
    }
    SampleResult.setResponseCode('200')
    SampleResult.setSuccessful(true)
} catch(PreparationRuntime.Failure failure) {
    SampleResult.setResponseCode(failure.code)
    SampleResult.setSuccessful(false)
} catch(LinkageError ignored) {
    SampleResult.setResponseCode('PREPARE_TOOL_UNAVAILABLE')
    SampleResult.setSuccessful(false)
} catch(Exception ignored) {
    SampleResult.setResponseCode('PREPARE_INPUT_INVALID')
    SampleResult.setSuccessful(false)
}

if (!SampleResult.isSuccessful() && vars.getObject('oct.flowRunState') != null) {
    synchronized(props) {
        if(vars.get('oct.isCleanup') != 'true') vars.getObject('oct.flowRunState').failed = true
    }
}
