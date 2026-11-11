package io.yuan.octopus.service;

import io.yuan.octopus.dto.response.CallChainDTO;
import io.yuan.octopus.dto.response.CallChainDTO.CallChainNodeDTO;
import io.yuan.octopus.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;
import org.neo4j.driver.types.Node;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * M9：调用链改为按层 BFS 展开可达子图后，必须
 * <ul>
 *   <li>保持原有调用树/路径语义（含菱形依赖展开为多条路径、同一路径去环）；</li>
 *   <li>每个节点只向 Neo4j 展开一次（查询成本线性）；</li>
 *   <li>maxDepth 与节点上限兜底生效。</li>
 * </ul>
 * Neo4j 用内存假图模拟：start 查询返回入口节点，expand 查询按 $frontier 返回出边。
 */
class QueryServiceCallChainTest {

    private Driver driver;
    private Session session;
    private QueryService service;

    /** 假图：signature → 出边（child signature, callType）。 */
    private final Map<String, List<String[]>> graph = new LinkedHashMap<>();
    private final Map<String, Node> nodes = new HashMap<>();
    private final List<List<String>> expandCalls = new ArrayList<>();

    @BeforeEach
    void setUp() {
        driver = mock(Driver.class);
        session = mock(Session.class);
        when(driver.session()).thenReturn(session);
        service = new QueryService(driver,
                mock(ClassInfoRepository.class), mock(MethodInfoRepository.class),
                mock(FieldInfoRepository.class), mock(AnnotationInfoRepository.class),
                mock(InheritanceRelationRepository.class), mock(ClassDependencyRepository.class),
                mock(ClassInterfaceRelationRepository.class), mock(AnalyzedProjectRepository.class),
                mock(GitService.class), mock(RepoConfigService.class), mock(RepositoryScopeService.class));
        when(session.run(anyString(), anyMap())).thenAnswer(inv -> run(inv.getArgument(0), inv.getArgument(1)));
    }

    private void edge(String from, String to, String type) {
        node(from);
        node(to);
        graph.computeIfAbsent(from, k -> new ArrayList<>()).add(new String[]{to, type});
    }

    private Node node(String sig) {
        return nodes.computeIfAbsent(sig, s -> {
            Node n = mock(Node.class);
            lenient().when(n.elementId()).thenReturn("id:" + s);
            Map<String, Value> props = Map.of(
                    "signature", Values.value("com.x." + s + ".run():void"),
                    "qualifiedClassName", Values.value("com.x." + s),
                    "methodName", Values.value("run"));
            lenient().when(n.get(anyString())).thenAnswer(i -> props.getOrDefault(i.getArgument(0), Values.value((Object) null)));
            return n;
        });
    }

    private static String sigOf(String name) {
        return "com.x." + name + ".run():void";
    }

    @SuppressWarnings("unchecked")
    private Result run(String cypher, Map<String, Object> params) {
        Result result = mock(Result.class);
        if (cypher.contains("RETURN s ORDER BY")) {
            String cls = (String) params.get("className");
            List<Node> starts = nodes.containsKey(cls.substring("com.x.".length()))
                    ? List.of(nodes.get(cls.substring("com.x.".length()))) : List.of();
            when(result.list(any(Function.class))).thenAnswer(inv -> {
                Function<Record, Object> f = inv.getArgument(0);
                List<Object> out = new ArrayList<>();
                for (Node n : starts) {
                    Record r = mock(Record.class);
                    Value v = mock(Value.class);
                    when(v.asNode()).thenReturn(n);
                    when(r.get("s")).thenReturn(v);
                    out.add(f.apply(r));
                }
                return out;
            });
            return result;
        }
        List<String> frontier = (List<String>) params.get("frontier");
        expandCalls.add(new ArrayList<>(frontier));
        List<Record> records = new ArrayList<>();
        for (String id : frontier) {
            String parent = id.substring("id:".length());
            for (String[] e : graph.getOrDefault(parent, List.of())) {
                Record r = mock(Record.class);
                Value child = mock(Value.class);
                when(child.asNode()).thenReturn(nodes.get(e[0]));
                when(r.get("parentSig")).thenReturn(Values.value(sigOf(parent)));
                when(r.get("callType")).thenReturn(Values.value(e[1]));
                when(r.get("lineNumber")).thenReturn(Values.value(1));
                when(r.get("c")).thenReturn(child);
                records.add(r);
            }
        }
        Iterator<Record> it = records.iterator();
        when(result.hasNext()).thenAnswer(i -> it.hasNext());
        when(result.next()).thenAnswer(i -> it.next());
        return result;
    }

    private static int countPaths(List<CallChainNodeDTO> items) {
        int n = 0;
        for (CallChainNodeDTO c : items) {
            n += c.children().isEmpty() ? 1 : countPaths(c.children());
        }
        return n;
    }

    @Test
    void diamondAndCycleKeepPathSemanticsAndExpandEachNodeOnce() {
        // A → B → D, A → C → D, D → A（环）
        edge("A", "B", "DIRECT");
        edge("A", "C", "DIRECT");
        edge("B", "D", "DIRECT");
        edge("C", "D", "INTERFACE");
        edge("D", "A", "DIRECT");

        CallChainDTO dto = service.getCallChain("com.x.A", "run", List.of(), 0, null, null);

        assertFalse(dto.truncated());
        assertEquals(sigOf("A"), dto.root().signature());
        assertEquals(2, dto.chain().size());
        // 菱形展开为两条路径 A-B-D-A、A-C-D-A；回到 A 的边保留为叶子，但不再继续展开（同一路径去环）
        assertEquals(2, countPaths(dto.chain()));
        CallChainNodeDTO b = dto.chain().get(0);
        CallChainNodeDTO d = b.children().get(0);
        assertEquals(sigOf("D"), d.method().signature());
        assertEquals(1, d.children().size());
        assertEquals(sigOf("A"), d.children().get(0).method().signature());
        assertTrue(d.children().get(0).children().isEmpty());

        // 每个节点只展开一次：A；B,C；D —— 共 3 层，4 个节点
        Set<String> all = new HashSet<>();
        expandCalls.forEach(all::addAll);
        assertEquals(Set.of("id:A", "id:B", "id:C", "id:D"), all);
        assertEquals(4, expandCalls.stream().mapToInt(List::size).sum());
    }

    @Test
    void maxDepthLimitsLevels() {
        edge("A", "B", "DIRECT");
        edge("B", "C", "DIRECT");
        edge("C", "D", "DIRECT");

        CallChainDTO dto = service.getCallChain("com.x.A", "run", List.of(), 2, null, null);

        CallChainNodeDTO b = dto.chain().get(0);
        CallChainNodeDTO c = b.children().get(0);
        assertEquals(sigOf("C"), c.method().signature());
        assertTrue(c.children().isEmpty(), "depth 2 stops after A→B→C");
        assertEquals(2, expandCalls.size());
    }

    @Test
    void unlimitedDepthFollowsWholeChain() {
        for (int i = 0; i < 30; i++) edge("N" + i, "N" + (i + 1), "DIRECT");

        CallChainDTO dto = service.getCallChain("com.x.N0", "run", List.of(), 0, null, null);

        int depth = 0;
        List<CallChainNodeDTO> level = dto.chain();
        while (!level.isEmpty()) { depth++; level = level.get(0).children(); }
        assertEquals(30, depth);
    }

    @Test
    void treeNodeBudgetTruncatesAndFlags() {
        edge("A", "B", "DIRECT");
        edge("A", "C", "DIRECT");
        edge("A", "D", "DIRECT");
        ReflectionTestUtils.setField(service, "callChainMaxTreeNodes", 2);

        CallChainDTO dto = service.getCallChain("com.x.A", "run", List.of(), 0, null, null);

        assertTrue(dto.truncated());
        assertEquals(2, dto.chain().size());
    }

    @Test
    void unknownMethodReturnsEmptyChain() {
        CallChainDTO dto = service.getCallChain("com.x.Missing", "run", List.of(), 0, null, null);
        assertTrue(dto.chain().isEmpty());
        assertFalse(dto.truncated());
        assertTrue(expandCalls.isEmpty());
    }
}
