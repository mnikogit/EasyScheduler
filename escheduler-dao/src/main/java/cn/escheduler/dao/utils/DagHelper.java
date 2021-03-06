/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cn.escheduler.dao.utils;


import cn.escheduler.common.enums.TaskDependType;
import cn.escheduler.common.model.TaskNode;
import cn.escheduler.common.model.TaskNodeRelation;
import cn.escheduler.common.process.ProcessDag;
import cn.escheduler.common.utils.JSONUtils;
import cn.escheduler.dao.model.ProcessData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * dag tools
 */
public class DagHelper {


    private static final Logger logger = LoggerFactory.getLogger(DagHelper.class);

    /**
     * generate flow node relation list by task node list;
     * Edges that are not in the task Node List will not be added to the result
     * 根据task Node List生成node关系列表,不在task Node List中的边不会被添加到结果中
     *
     * @param taskNodeList
     * @return
     */
    private static List<TaskNodeRelation> generateRelationListByFlowNodes(List<TaskNode> taskNodeList) {
        List<TaskNodeRelation> nodeRelationList = new ArrayList<>();
        for (TaskNode taskNode : taskNodeList) {
            String preTasks = taskNode.getPreTasks();
            List<String> preTaskList = JSONUtils.toList(preTasks, String.class);
            if (preTaskList != null) {
                for (String depNodeName : preTaskList) {
                    if (null != findNodeByName(taskNodeList, depNodeName)) {
                        nodeRelationList.add(new TaskNodeRelation(depNodeName, taskNode.getName()));
                    }
                }
            }
        }
        return nodeRelationList;
    }

    /**
     * generate task nodes needed by dag
     * 生成dag需要的task nodes
     *
     * @param taskNodeList
     * @param taskDependType
     * @return
     */
    private static List<TaskNode> generateFlowNodeListByStartNode(List<TaskNode> taskNodeList, List<String> startNodeNameList,
                                                                  List<String> recoveryNodeNameList, TaskDependType taskDependType) {
        List<TaskNode> destFlowNodeList = new ArrayList<>();
        List<String> startNodeList = startNodeNameList;

        if(taskDependType != TaskDependType.TASK_POST
                && startNodeList.size() == 0){
            logger.error("start node list is empty! cannot continue run the process ");
            return destFlowNodeList;
        }
        List<TaskNode> destTaskNodeList = new ArrayList<>();
        List<TaskNode> tmpTaskNodeList = new ArrayList<>();
        if (taskDependType == TaskDependType.TASK_POST
                && recoveryNodeNameList.size() > 0) {
            startNodeList = recoveryNodeNameList;
        }
        if (startNodeList == null || startNodeList.size() == 0) {
            // 没有特殊的指定start nodes
            tmpTaskNodeList = taskNodeList;
        } else {
            // 指定了start nodes or 恢复执行
            for (String startNodeName : startNodeList) {
                TaskNode startNode = findNodeByName(taskNodeList, startNodeName);
                List<TaskNode> childNodeList = new ArrayList<>();
                if (TaskDependType.TASK_POST == taskDependType) {
                    childNodeList = getFlowNodeListPost(startNode, taskNodeList);
                } else if (TaskDependType.TASK_PRE == taskDependType) {
                    childNodeList = getFlowNodeListPre(startNode, recoveryNodeNameList, taskNodeList);
                } else {
                    childNodeList.add(startNode);
                }
                tmpTaskNodeList.addAll(childNodeList);
            }
        }

        for (TaskNode taskNode : tmpTaskNodeList) {
            if ( !taskNode.isForbidden()
                    && null == findNodeByName(destTaskNodeList, taskNode.getName())) {
                destTaskNodeList.add(taskNode);
            }
        }
        return destTaskNodeList;
    }


    /**
     * find all the nodes that depended on the start node
     * 找到所有依赖start node的node
     *
     * @param startNode
     * @param taskNodeList
     * @return
     */
    private static List<TaskNode> getFlowNodeListPost(TaskNode startNode, List<TaskNode> taskNodeList) {
        List<TaskNode> resultList = new ArrayList<>();
        for (TaskNode taskNode : taskNodeList) {
            List<String> depList = taskNode.getDepList();
            if (depList != null) {
                if (depList.contains(startNode.getName())) {
                    resultList.addAll(getFlowNodeListPost(taskNode, taskNodeList));
                }
            }

        }
        resultList.add(startNode);
        return resultList;
    }

    /**
     * find all nodes that start nodes depend on.
     * 找到所有start node依赖的node
     *
     * @param startNode
     * @param taskNodeList
     * @return
     */
    private static List<TaskNode> getFlowNodeListPre(TaskNode startNode, List<String> recoveryNodeNameList, List<TaskNode> taskNodeList) {

        List<TaskNode> resultList = new ArrayList<>();

        List<String> depList = startNode.getDepList();
        resultList.add(startNode);
        if (depList == null || depList.size() == 0) {
            return resultList;
        }
        for (String depNodeName : depList) {
            TaskNode start = findNodeByName(taskNodeList, depNodeName);
            if (recoveryNodeNameList.contains(depNodeName)) {
                resultList.add(start);
            } else {
                resultList.addAll(getFlowNodeListPre(start, recoveryNodeNameList, taskNodeList));
            }
        }
        return resultList;
    }

    /**
     * generate dag by start nodes and recovery nodes
     * 根据start nodes 和 recovery nodes 生成dag
     * @param processDefinitionJson
     * @param startNodeNameList
     * @param recoveryNodeNameList
     * @param depNodeType
     * @return
     * @throws Exception
     */
    public static ProcessDag generateFlowDag(String processDefinitionJson,
                                             List<String> startNodeNameList,
                                             List<String> recoveryNodeNameList,
                                             TaskDependType depNodeType) throws Exception {
        ProcessData processData = JSONUtils.parseObject(processDefinitionJson, ProcessData.class);

        List<TaskNode> taskNodeList = processData.getTasks();
        List<TaskNode> destTaskNodeList = generateFlowNodeListByStartNode(taskNodeList, startNodeNameList, recoveryNodeNameList, depNodeType);
        if (destTaskNodeList.isEmpty()) {
            return null;
        }
        List<TaskNodeRelation> taskNodeRelations = generateRelationListByFlowNodes(destTaskNodeList);
        ProcessDag processDag = new ProcessDag();
        processDag.setEdges(taskNodeRelations);
        processDag.setNodes(destTaskNodeList);
        return processDag;
    }


    /**
     * find node by node name
     * 通过 name 获取节点
     * @param nodeDetails
     * @param nodeName
     * @return
     * @see TaskNode
     */
    public static TaskNode findNodeByName(List<TaskNode> nodeDetails, String nodeName) {
        for (TaskNode taskNode : nodeDetails) {
            if (taskNode.getName().equals(nodeName)) {
                return taskNode;
            }
        }
        return null;
    }
}
