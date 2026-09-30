package com.bot.bot.analysis;

import com.bot.bot.domain.ChangeChunk;
import com.bot.bot.domain.Finding;
import com.bot.bot.domain.PullRequestContext;

import java.util.List;

public interface Rule {
    List<Finding> analyze(List<ChangeChunk> chunks);

    default List<Finding> analyze(List<ChangeChunk> chunks, PullRequestContext prContext) {
        return analyze(chunks);
    }

    String getName();
}
