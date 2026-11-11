package io.yuan.octopus.scenario.testing;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@Service
public class RunSubmissionStore {
    @ResponseStatus(HttpStatus.CONFLICT)
    public static class Conflict extends RuntimeException {public Conflict(String message){super(message);}}
    private final RunSubmissionRepository submissions;
    private final BusinessTestRunRepository runs;
    public RunSubmissionStore(RunSubmissionRepository submissions,BusinessTestRunRepository runs){this.submissions=submissions;this.runs=runs;}
    public BusinessTestRun find(String key,String inputHash) {
        return submissions.findById(key).map(receipt->{
            if(!receipt.inputHash.equals(inputHash)) throw new Conflict("同一幂等键对应不同运行参数，请刷新并创建新的提交");
            return runs.findById(receipt.runId).orElseThrow(()->new Conflict("运行记录不可用，请检查原运行；不能自动重放"));
        }).orElse(null);
    }
    @Transactional
    public BusinessTestRun reserve(String key,String inputHash,BusinessTestRun run) {
        var saved=runs.saveAndFlush(run);
        var receipt=new RunSubmission();receipt.id=key;receipt.inputHash=inputHash;receipt.runId=saved.id;receipt.createdAt=saved.createdAt;
        submissions.saveAndFlush(receipt);
        return saved;
    }
}
