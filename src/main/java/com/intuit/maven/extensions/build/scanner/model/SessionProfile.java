package com.intuit.maven.extensions.build.scanner.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Builder;
import lombok.Data;
import lombok.NonNull;
import org.apache.maven.project.MavenProject;

@Data
@Builder
public class SessionProfile {
  @NonNull private final String id;
  @NonNull private final Project project;
  @NonNull private final String command, hostname, username;
  @NonNull private final List<String> goals;
  private final String branch;
  private final List<ProjectProfile> projectProfiles = new ArrayList<>();
  @JsonIgnore
  private final Map<Project, Long> projectToEndTime = new HashMap<>();
  private long startTime, endTime;
  @NonNull private Status status;

  public void addProjectProfile(ProjectProfile projectProfile) {
    projectProfiles.add(projectProfile);
  }

  public void endProject(Project project, Long time) {
    projectToEndTime.put(project, time);
  }

  public Long getProjectEndTime(Project project) {
    return projectToEndTime.get(project);
  }

  public ProjectProfile getProjectProfile(MavenProject mavenProject) {
    return projectProfiles
        .stream()
        .filter(candidate -> candidate.getProject().getGroupId().equals(mavenProject.getGroupId()) &&
                candidate.getProject().getArtifactId().equals(mavenProject.getArtifactId()))
        .findFirst()
        .get();
  }

  public void setStartTime(long startTime) {
    this.startTime = startTime;
    this.endTime = startTime;
  }

  public long getDuration() {
    return endTime - startTime;
  }
}
