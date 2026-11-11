package io.yuan.octopus.scenario.testing;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController
@RequestMapping("/api/business-testing/documentation")
public class ApiDocumentationController {
    private final ApiDocumentationService service;
    public ApiDocumentationController(ApiDocumentationService service) {this.service=service;}
    @PostMapping("/export") public ApiDocumentationService.Document export(@RequestBody ApiDocumentationService.ExportRequest request) {return service.export(request);}
    @GetMapping("/compare") public List<ApiDocumentationService.Difference> compare(@RequestParam String before,@RequestParam String after) {return service.compare(before,after);}
    @GetMapping("/capabilities") public Map<String,Object> capabilities() {return Map.of("openApiVersions",List.of("3.0","3.1"),"completeVersionSupport",false,
            "formats",List.of("JSON"),"parameters",List.of("scalar-path","scalar-query","scalar-header"),"mediaTypes",List.of("application/json","no-body"),
            "unsupported",List.of("remote-refs","recursive-refs","union-types","multipart","binary","xml"));}
}
