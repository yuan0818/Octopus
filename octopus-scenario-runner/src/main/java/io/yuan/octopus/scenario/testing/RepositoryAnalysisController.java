package io.yuan.octopus.scenario.testing;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/business-testing/repository-analysis")
public class RepositoryAnalysisController {
    private final RepositoryAnalysisService service;
    public RepositoryAnalysisController(RepositoryAnalysisService service){this.service=service;}
    @GetMapping("/tasks") public RepositoryAnalysisTasks.Page tasks(@RequestParam(required=false) String repositoryKey,@RequestParam(required=false) String modulePath,@RequestParam(required=false) String branch,@RequestParam(required=false) String commitId,@RequestParam(required=false) String environment,@RequestParam(required=false) String state,@RequestParam(required=false) String search,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize){return service.tasks(repositoryKey,modulePath,branch,commitId,environment,state,search,page,pageSize);}
    @GetMapping("/tasks/{id}") public RepositoryAnalysisTasks.Detail task(@PathVariable String id){return service.task(id);}
    @PostMapping("/tasks/{id}/resume") public RepositoryAnalysisService.View resume(@PathVariable String id){return service.resumeTask(id);}
    @PostMapping("/tasks/{id}/cancel") public RepositoryAnalysisService.View cancelTask(@PathVariable String id){return service.cancelTask(id);}
    @PostMapping("/tasks/{id}/adopt") public RepositoryAnalysisService.AdoptedBackground adopt(@PathVariable String id){return service.adoptTask(id);}
    @GetMapping("/adopted") public RepositoryAnalysisService.AdoptedBackground adopted(@RequestParam String repositoryKey,@RequestParam String branch,@RequestParam String commitId){return service.adopted(new RepositoryAnalysisService.Request(repositoryKey,branch,commitId,null,""));}
    @GetMapping public RepositoryAnalysisService.View get(@RequestParam String repositoryKey,@RequestParam String branch,@RequestParam String commitId,@RequestParam(required=false) String environment){return service.get(new RepositoryAnalysisService.Request(repositoryKey,branch,commitId,environment,""));}
    @PostMapping public RepositoryAnalysisService.View start(@RequestBody RepositoryAnalysisService.Request request){return service.start(request);}
    @PostMapping("/cancel") public RepositoryAnalysisService.View cancel(@RequestBody RepositoryAnalysisService.Request request){return service.cancel(request);}
}
