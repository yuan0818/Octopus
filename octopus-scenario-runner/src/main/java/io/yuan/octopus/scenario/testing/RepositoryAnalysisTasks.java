package io.yuan.octopus.scenario.testing;
import java.util.*;
import static io.yuan.octopus.scenario.testing.RepositoryAnalysisService.*;
public final class RepositoryAnalysisTasks {
    private RepositoryAnalysisTasks() {}
    public record Summary(String taskId,boolean current,String repositoryKey,String repositoryName,String branch,String commitId,@com.fasterxml.jackson.annotation.JsonIgnore String environment,List<String> modulePaths,String goal,String model,String state,String createdAt,String updatedAt,int completed,int total,String currentTask,FlowFailure.Detail failure) {}
    public record Detail(String taskId,boolean current,View analysis,boolean adopted,String adoptedAt) {}
    public record Page(List<Summary> items,int total,int page,int pageSize) {}
    static String taskId(Run run){return run.taskId!=null?run.taskId:TestingJson.hash(List.of(run.request.environment()==null?id(run.request):TestingJson.hash(List.of(run.request.repositoryKey(),run.request.branch(),run.request.commitId(),run.request.environment())),run.createdAt));}
    static Summary summary(Run r,boolean current) {
        var request=r.request;var tasks=r.tasks==null?List.<RepositoryAnalysisWorkflow.TaskRun>of():r.tasks;
        return new Summary(taskId(r),current,request.repositoryKey(),r.inventory!=null?r.inventory.name():r.packet!=null?r.packet.name():null,request.branch(),request.commitId(),request.environment(),
            (r.inventory!=null?r.inventory.modules():r.packet!=null?r.packet.modules():List.<RepositoryCatalogClient.Module>of()).stream().map(RepositoryCatalogClient.Module::modulePath).distinct().toList(),request.goal(),r.model==null?request.model():r.model,r.state,r.createdAt,r.updatedAt,
            (int)tasks.stream().filter(t->t.memory!=null).count(),tasks.size(),tasks.stream().filter(t->Objects.equals(t.task.id(),r.currentTaskId)).map(t->t.task.title()).findFirst().orElse(null),r.failure);
    }
}
