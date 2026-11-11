package io.yuan.octopus.service;

import org.springframework.stereotype.Service;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import java.util.*;

/** Directory-first discovery and explicit file reads. No framework or class-name scoring. */
@Service
public class RepositoryExplorationService {
    public record Inventory(String repositoryKey, String name, String branch, String commitId, String environment,
                            List<RepositoryScopeService.Module> modules, List<String> files, List<String> warnings) {}
    public record ReadRequest(String repositoryKey, String branch, String commitId, String environment, List<String> files) {}
    private final RepositoryScopeService scopes;
    private final GitMethodSourceClient source;
    public RepositoryExplorationService(RepositoryScopeService scopes, GitMethodSourceClient source) { this.scopes=scopes; this.source=source; }

    public Inventory inventory(String key, String branch, String commit, String environment) {
        if (commit==null || !commit.matches("[a-fA-F0-9]{40}")) throw new IllegalArgumentException("请选择仓库版本与环境");
        var scope=scopes.scopes().stream().filter(s->s.key().equals(key)).findFirst().orElseThrow(()->new IllegalArgumentException("仓库不存在"));
        var version=scopes.versions(key,branch,null).stream().filter(v->v.commitId().equals(commit)).findFirst().orElseThrow(()->new IllegalArgumentException("尚未分析此提交"));
        var ids=new HashSet<>(version.projectIds()); ids.addAll(version.missingProjectIds());
        var modules=new TreeMap<String,RepositoryScopeService.Module>();
        scope.modules().stream().filter(m->ids.contains(m.projectId())).forEach(m->modules.putIfAbsent(m.modulePath(),m));
        if (modules.isEmpty() || modules.size()>64) throw new IllegalArgumentException("仓库编排支持一至六十四个模块");
        var files=source.tree(version.projectIds().getFirst(),commit);
        var warnings=new ArrayList<String>();
        if (!version.missingProjectIds().isEmpty()) warnings.add("部分模块缺少类型索引；本次仍读取同一提交的目录和文件，相关契约覆盖需确认");
        warnings.add("源码按模型计划逐项读取；只覆盖已读取文件。未读取的文件、动态部署配置和运行时凭据不能视为已分析。");
        return new Inventory(key,scope.name(),branch,commit,null,List.copyOf(modules.values()),files,List.copyOf(warnings));
    }

    public List<EndpointContractService.SourceEvidence> read(ReadRequest request) {
        if(request.files()==null || request.files().isEmpty() || request.files().size()>6 || new HashSet<>(request.files()).size()!=request.files().size()) throw new IllegalArgumentException("每次读取一至六个不同文件");
        var index=inventory(request.repositoryKey(),request.branch(),request.commitId(),request.environment());
        if(!index.files().containsAll(request.files())) throw new IllegalArgumentException("文件不属于所选仓库提交");
        var result=new ArrayList<EndpointContractService.SourceEvidence>();
        var parser=new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE));
        for(String file:request.files()) {
            // Membership in the Git tree also excludes absolute paths, traversal and symlink targets.
            var owner=index.modules().stream().filter(m->m.modulePath().equals(".") || file.startsWith(m.modulePath()+"/"))
                    .max(Comparator.comparingInt(m->m.modulePath().length())).orElse(index.modules().getFirst());
            boolean supported=file.endsWith(".java") || file.endsWith("pom.xml") || file.endsWith(".yml") || file.endsWith(".yaml") || file.endsWith(".json") || file.endsWith(".properties");
            var texts=supported?source.fetchBatch(owner.projectId(),request.commitId(),List.of(new GitMethodSourceClient.SourceItem(file,1,4000))):List.of("");
            if(texts.isEmpty() || texts.getFirst()==null) throw new IllegalStateException("所选文件暂不可读取");
            String raw=texts.getFirst(), safe; String kind="REPOSITORY_CODE";
            if(file.endsWith(".java")) {
                var parsed=parser.parse(raw).getResult();
                if(parsed.isPresent())safe=ModelSourceSanitizer.sanitizeRepository(parsed.get());
                else {safe="源码未能解析，不能据此得出实现结论";kind="UNSUPPORTED";}
            }
            else if(file.endsWith("pom.xml")) { safe=RepositoryBackgroundService.dependencies(raw); kind="MODULE_DEPENDENCIES"; }
            else if(file.endsWith(".yml") || file.endsWith(".yaml") || file.endsWith(".json") || file.endsWith(".properties")) {
                safe=new com.google.gson.Gson().toJson(Map.of("configuration",ConfigurationEvidence.project(raw,file.endsWith(".properties")),"routeProjection",file.endsWith(".properties")?"":RepositoryBackgroundService.routes(raw))); kind="CONFIG_SCHEMA";
            }
            else { safe="当前安全读取器不支持此文件类型；只知道文件存在，内容尚未分析"; kind="UNSUPPORTED"; }
            boolean truncated=raw.lines().count()>=4000 || safe.length()>14000;
            safe=safe.substring(0,Math.min(14000,safe.length()));
            String id=UUID.nameUUIDFromBytes((request.commitId()+":"+file).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
            result.add(new EndpointContractService.SourceEvidence(id,owner.projectId(),request.commitId(),file,1,(int)Math.max(1,raw.lines().count()),kind,safe,truncated));
        }
        return result;
    }
    static List<String> configurationKeys(String text) {
        var options=new org.yaml.snakeyaml.LoaderOptions();options.setCodePointLimit(200000);options.setMaxAliasesForCollections(20);
        var result=new ArrayList<String>();
        try { keys(new org.yaml.snakeyaml.Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor(options)).load(text),"",result,0); }
        catch(RuntimeException e){result.add("配置无法安全解析，内容未提供");}
        return result;
    }
    private static void keys(Object value,String prefix,List<String> result,int depth) {
        if(depth>12 || result.size()>=500)return;
        if(value instanceof Map<?,?> map)for(var entry:map.entrySet()) {
            String key=String.valueOf(entry.getKey());if(!key.matches("[A-Za-z_][A-Za-z0-9_.-]{0,100}"))continue;
            String path=prefix.isEmpty()?key:prefix+"."+key;
            if(result.size()>=500)return;result.add(path);keys(entry.getValue(),path,result,depth+1);
        } else if(value instanceof List<?> list && !list.isEmpty())keys(list.getFirst(),prefix+"[]",result,depth+1);
    }
}
