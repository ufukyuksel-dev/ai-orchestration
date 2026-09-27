package com.mbworldwideapps.aiorchestration.core.judge;

public interface JudgeRubric {

    String id();

    JudgeResult judge(JudgeRequest request);
}
