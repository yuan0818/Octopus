package io.yuan.octopus.scenario.testing;

/** Validate structure before applying any model proposal; diagnostics contain schema names only. */
final class StepModelJson {
    private StepModelJson() {}
    static StepSuggestionService.Patch parse(FlowModelClient.Response response) {
        var patch=RepositoryModelJson.parse(response,StepSuggestionService.Patch.class);
        required(patch.node(),"Patch.node");
        required(patch.node().id(),"Patch.node.id");
        required(patch.node().inputs(),"Patch.node.inputs");
        required(patch.node().outputs(),"Patch.node.outputs");
        required(patch.assertions(),"Patch.assertions");
        required(patch.assumptions(),"Patch.assumptions");
        required(patch.questions(),"Patch.questions");
        required(patch.memory(),"Patch.memory");
        required(patch.memory().summary(),"Patch.memory.summary");
        required(patch.memory().decisions(),"Patch.memory.decisions");
        required(patch.memory().questions(),"Patch.memory.questions");
        required(patch.memory().evidenceIds(),"Patch.memory.evidenceIds");
        patch.node().inputs().values().forEach(v->required(v,"Patch.node.inputs[*]"));
        for(var a:patch.assertions())required(a.assertion(),"Patch.assertions[*].assertion");
        return patch;
    }
    private static void required(Object value,String path) {
        if(value==null)throw RepositoryModelJson.invalid("SCHEMA_REQUIRED："+path+" 缺失或为 null");
        if(value instanceof java.util.List<?> list && list.stream().anyMatch(java.util.Objects::isNull))
            throw RepositoryModelJson.invalid("SCHEMA_REQUIRED："+path+" 数组不能含 null");
    }
}
