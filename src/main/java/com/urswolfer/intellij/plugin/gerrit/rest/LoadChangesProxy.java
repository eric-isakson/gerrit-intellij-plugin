/*
 * Copyright 2013-2014 Urs Wolfer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.urswolfer.intellij.plugin.gerrit.rest;

import com.google.common.base.Throwables;
import com.google.common.collect.Lists;
import com.google.gerrit.extensions.api.changes.Changes;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.intellij.openapi.project.Project;
import com.intellij.util.Consumer;

import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Thomas Forrer
 */
public class LoadChangesProxy {
    private static final int PAGE_SIZE = 25;

    private final Changes.QueryRequest queryRequest;
    private final GerritUtil gerritUtil;
    private final Project project;
    private int start = 0;
    private boolean hasMore = true;
    private final List<ChangeInfo> changes = Lists.newArrayList();
    private final Lock lock = new ReentrantLock();

    public LoadChangesProxy(Changes.QueryRequest queryRequest,
                            GerritUtil gerritUtil,
                            Project project) {
        this.queryRequest = queryRequest;
        this.gerritUtil = gerritUtil;
        this.project = project;
    }

    /**
     * @return all changes satisfying the provided query
     */
    public List<ChangeInfo> getChanges() {
        try {
            return queryRequest.withLimit(-1).get();
        } catch (RestApiException e) {
            throw Throwables.propagate(e);
        }
    }

    /**
     * Load the next page of changes into the provided consumer
     */
    public void getNextPage(final Consumer<List<ChangeInfo>> consumer) {
        lock.lock();
        if (hasMore) {
            gerritUtil.isChangesStartSupported(project, new Consumer<Boolean>() {
                @Override
                public void consume(Boolean supportsChangesStart) {
                    // gerrit servers prior to 2.9 do not support the S/start parameter on changes endpoint
                    // they use resume_sortkey instead
                    Changes.QueryRequest myRequest = queryRequest.withLimit(PAGE_SIZE);;
                    if (supportsChangesStart) {
                        myRequest = myRequest.withStart(start);
                    } else {
                        int changeCount = changes.size();
                        if (changeCount != 0) {
                            ChangeInfo lastChange = changes.get(changeCount - 1);
                            myRequest = gerritUtil.updateResumeSortKey(myRequest, lastChange._sortkey);
                        }
                    }

                    Consumer<List<ChangeInfo>> myConsumer = new Consumer<List<ChangeInfo>>() {
                        @Override
                        public void consume(List<ChangeInfo> changeInfos) {
                            hasMore = changeInfos.size() == PAGE_SIZE;
                            changes.addAll(changeInfos);
                            start += PAGE_SIZE;
                            consumer.consume(changeInfos);
                            lock.unlock();
                        }
                    };
                    gerritUtil.getChanges(myRequest, project, myConsumer);
                }
            });
        }
    }
}
