package com.yangtze.bankwarning.ai.workflow;

import java.util.List;

public class PlanProgress {

    private String planName;
    private String taskId;
    private String status;
    private int totalSteps;
    private int completedSteps;
    private List<SubTaskProgress> subtasks;

    public PlanProgress() {
    }

    public PlanProgress(String planName, String taskId, String status,
                        int totalSteps, int completedSteps,
                        List<SubTaskProgress> subtasks) {
        this.planName = planName;
        this.taskId = taskId;
        this.status = status;
        this.totalSteps = totalSteps;
        this.completedSteps = completedSteps;
        this.subtasks = subtasks;
    }

    public String getPlanName() { return planName; }
    public void setPlanName(String planName) { this.planName = planName; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getTotalSteps() { return totalSteps; }
    public void setTotalSteps(int totalSteps) { this.totalSteps = totalSteps; }
    public int getCompletedSteps() { return completedSteps; }
    public void setCompletedSteps(int completedSteps) { this.completedSteps = completedSteps; }
    public List<SubTaskProgress> getSubtasks() { return subtasks; }
    public void setSubtasks(List<SubTaskProgress> subtasks) { this.subtasks = subtasks; }

    public static class SubTaskProgress {
        private int index;
        private String name;
        private String description;
        private String state;
        private String expectedOutcome;
        private String outcome;

        public SubTaskProgress() {}

        public SubTaskProgress(int index, String name, String description,
                               String state, String expectedOutcome, String outcome) {
            this.index = index;
            this.name = name;
            this.description = description;
            this.state = state;
            this.expectedOutcome = expectedOutcome;
            this.outcome = outcome;
        }

        public int getIndex() { return index; }
        public void setIndex(int index) { this.index = index; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getState() { return state; }
        public void setState(String state) { this.state = state; }
        public String getExpectedOutcome() { return expectedOutcome; }
        public void setExpectedOutcome(String expectedOutcome) { this.expectedOutcome = expectedOutcome; }
        public String getOutcome() { return outcome; }
        public void setOutcome(String outcome) { this.outcome = outcome; }
    }
}
