package io.yuan.octopus.service;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.google.gson.Gson;
import io.yuan.octopus.entity.ClassInfo;
import io.yuan.octopus.repository.ClassInfoRepository;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Repository-wide, version-pinned evidence, independent of the controller call graph. */
@Service
public class RepositoryBackgroundService {
    public record Packet(String repositoryKey,String name,String branch,String commitId,String environment,
                         List<RepositoryScopeService.Module> modules,List<EndpointContractService.SourceEvidence> sources,List<String> warnings) {}
    private final RepositoryScopeService scopes;
    private final ClassInfoRepository classes;
    private final GitMethodSourceClient source;
    public RepositoryBackgroundService(RepositoryScopeService scopes,ClassInfoRepository classes,GitMethodSourceClient source) { this.scopes=scopes;this.classes=classes;this.source=source; }
    public Packet collect(String key,String branch,String commit,String environment) {
        if(commit==null || !commit.matches("[a-fA-F0-9]{40}") || environment==null || !environment.matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("请选择已分析的提交和环境");
        var scope=scopes.scopes().stream().filter(s->s.key().equals(key)).findFirst().orElseThrow(()->new IllegalArgumentException("仓库不存在"));
        var version=scopes.versions(key,branch,null).stream().filter(v->v.commitId().equals(commit)).findFirst().orElseThrow(()->new IllegalArgumentException("该仓库分支尚未分析此提交"));
        var modules=scope.modules().stream().filter(m->version.projectIds().contains(m.projectId())).toList();
        var metadata=new ArrayList<ClassInfo>();modules.forEach(m->metadata.addAll(classes.findByProjectIdAndBranchAndCommitId(m.projectId(),branch,commit)));
        var warnings=new ArrayList<String>();
        if(!version.missingProjectIds().isEmpty())warnings.add("部分模块未分析此提交："+version.missingProjectIds());
        warnings.add("仅依据所选提交的静态源码与默认/所选环境路由配置；未读取 Nacos、部署配置或运行时密钥，不能据此断言线上入口配置。路由仅保留受支持的字符串形式规则，其他配置和外部地址不提供给模型。");
        var candidates=metadata.stream().filter(c->score(c)>0 && c.getSourceFilePath()!=null).sorted(Comparator.comparingInt(RepositoryBackgroundService::score).reversed().thenComparing(ClassInfo::getSourceFilePath)).toList();
        var files=new LinkedHashMap<String,Long>();candidates.forEach(c->files.putIfAbsent(c.getSourceFilePath(),c.getProjectId()));
        if(files.size()>60)warnings.add("相关源码超过 60 个文件，本次优先提供过滤器、拦截器、切面及签名加密工具，覆盖并非完整。");
        var evidence=new ArrayList<EndpointContractService.SourceEvidence>();int chars=0;
        var parser=new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE));
        for(var file:files.entrySet().stream().limit(60).toList()) {
            var texts=source.fetchBatch(file.getValue(),commit,List.of(new GitMethodSourceClient.SourceItem(file.getKey(),1,4000)));
            var text=texts.isEmpty()?null:texts.getFirst();if(text==null){warnings.add("源码不可读取："+relative(scope,file.getKey()));continue;}
            var parsed=parser.parse(text).getResult();if(parsed.isEmpty()){warnings.add("源码无法解析，未发送原始内容："+relative(scope,file.getKey()));continue;}
            String safe=ModelSourceSanitizer.sanitizeRepository(parsed.get());boolean truncated=safe.length()>14000;
            if(truncated)safe=safe.substring(0,14000);
            if(chars+safe.length()>160000){warnings.add("源码达到 160000 字符限制，剩余文件未读取。");break;}
            chars+=safe.length();String name=relative(scope,file.getKey());
            evidence.add(evidence(file.getValue(),commit,name,text.lines().count(),"REPOSITORY_CODE",safe,truncated));
        }
        // Resource files are not call-graph nodes. Read only known Spring gateway locations at the same commit.
        for(var module:modules.stream().filter(m->m.modulePath().toLowerCase(Locale.ROOT).contains("gateway")).limit(12).toList()) {
            String prefix=module.modulePath().equals(".")?"":module.modulePath()+"/";
            var paths=List.of(prefix+"src/main/resources/application.yaml",prefix+"src/main/resources/application.yml",prefix+"src/main/resources/application-"+environment+".yaml",prefix+"src/main/resources/application-"+environment+".yml");
            var texts=source.fetchBatch(module.projectId(),commit,paths.stream().map(p->new GitMethodSourceClient.SourceItem(p,1,4000)).toList());
            for(int i=0;i<texts.size();i++)if(texts.get(i)!=null) {
                String routes=routes(texts.get(i));if(!routes.isBlank())evidence.add(evidence(module.projectId(),commit,paths.get(i),texts.get(i).lines().count(),"GATEWAY_ROUTES",routes,false));
            }
        }
        for(var module:modules.stream().limit(40).toList()) {
            String file=(module.modulePath().equals(".")?"":module.modulePath()+"/")+"pom.xml";
            var texts=source.fetchBatch(module.projectId(),commit,List.of(new GitMethodSourceClient.SourceItem(file,1,4000)));
            if(!texts.isEmpty() && texts.getFirst()!=null) {String dependencies=dependencies(texts.getFirst());if(!dependencies.isBlank())evidence.add(evidence(module.projectId(),commit,file,texts.getFirst().lines().count(),"MODULE_DEPENDENCIES",dependencies,false));}
        }
        if(evidence.isEmpty())throw new IllegalArgumentException("此版本未找到可读取的仓库入口背景源码");
        return new Packet(key,scope.name(),branch,commit,environment,modules,evidence,warnings.stream().distinct().toList());
    }
    static int score(ClassInfo c) {
        String n=Objects.toString(c.getSimpleName(),"").toLowerCase(Locale.ROOT), p=Objects.toString(c.getSourceFilePath(),"").toLowerCase(Locale.ROOT);
        if(p.contains("/test/") || n.endsWith("test") || n.contains("cuckoo") || n.contains("bloom") || p.contains("/com/github/mgunlogson/") || p.contains("/com/mongodb/spark/"))return 0;
        if(n.contains("filter") || n.contains("interceptor") || n.contains("aspect"))return 100;
        if(n.contains("gateway") || n.contains("securityconfig") || n.contains("routeconfig"))return 90;
        if(p.contains("/crypto/") && !n.contains("test") || n.equals("cryptodata"))return 80;
        if(n.contains("tokenconfig") || n.contains("unauth") || n.equals("gatewaybodyutils") || n.equals("encrypt") || n.equals("decrypt"))return 75;
        return 0;
    }
    private static String relative(RepositoryScopeService.Scope scope,String file) {
        String prefix=scope.rootPath()+"/";return file.startsWith(prefix)?file.substring(prefix.length()):file;
    }
    private static EndpointContractService.SourceEvidence evidence(long project,String commit,String file,long lines,String kind,String text,boolean truncated) {
        try {String id=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((commit+":"+file).getBytes(StandardCharsets.UTF_8))).substring(0,24);
            return new EndpointContractService.SourceEvidence(id,project,commit,file,1,(int)Math.max(1,lines),kind,text,truncated);
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    /** Allowlisted routing information only; never forward whole YAML, URLs, config defaults or credentials. */
    static String routes(String raw) {
        try {
            var options=new LoaderOptions();options.setCodePointLimit(300000);options.setMaxAliasesForCollections(20);
            var result=new ArrayList<Map<String,Object>>();
            for(Object document:new Yaml(new SafeConstructor(options)).loadAll(raw)) {
                Object cursor=document;for(String key:List.of("spring","cloud","gateway","routes"))cursor=cursor instanceof Map<?,?> m?m.get(key):null;
                if(!(cursor instanceof List<?> routes))continue;
                for(Object item:routes.stream().limit(30).toList())if(item instanceof Map<?,?> route) {
                    var out=new LinkedHashMap<String,Object>();
                    String uri=Objects.toString(route.get("uri"),"");if(uri.matches("lb://[A-Za-z0-9_.-]{1,100}"))out.put("service",uri.substring(5));else out.put("service","[external or dynamic destination omitted]");
                    for(String key:List.of("predicates","filters"))if(route.get(key) instanceof List<?> values)out.put(key,values.stream().filter(String.class::isInstance).map(String.class::cast)
                            .filter(v->v.length()<400 && v.matches("(?:Path|Method|StripPrefix|PrefixPath|RewritePath|SetPath)=[A-Za-z0-9_/.*{},= ?<>$()\\\\-]+" )).toList());
                    result.add(out);
                }
            }
            return result.isEmpty()?"":new Gson().toJson(result);
        }catch(RuntimeException e){return "";}
    }
    static String dependencies(String raw) {
        try {
            var factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);factory.setExpandEntityReferences(false);
            var document=factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
            var names=new TreeSet<String>();for(String tag:List.of("groupId","artifactId")){var nodes=document.getElementsByTagName(tag);for(int i=0;i<nodes.getLength();i++){String v=nodes.item(i).getTextContent().trim();if(v.matches("[A-Za-z0-9_.-]{1,100}"))names.add(tag+":"+v);}}
            return new Gson().toJson(names);
        }catch(Exception e){return "";}
    }
}
