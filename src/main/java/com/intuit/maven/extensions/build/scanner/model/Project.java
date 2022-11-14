package com.intuit.maven.extensions.build.scanner.model;


import java.util.List;
import java.util.Objects;

public class Project {
  String groupId, artifactId;
  String version;

  private List<Dependency> dependencies;

  public Project(String groupId, String artifactId, String version, List<Dependency> dependencies) {
    this.groupId = groupId;
    this.artifactId = artifactId;
    this.version = version;
    this.dependencies = dependencies;
  }

  public String getId() {
    return groupId + ":" + artifactId;
  }

  public String getGroupId() {
    return groupId;
  }

  public void setGroupId(String groupId) {
    this.groupId = groupId;
  }

  public String getArtifactId() {
    return artifactId;
  }

  public void setArtifactId(String artifactId) {
    this.artifactId = artifactId;
  }

  public String getVersion() {
    return version;
  }

  public void setVersion(String version) {
    this.version = version;
  }

  public List<Dependency> getDependencies() {
    return dependencies;
  }

  public void setDependencies(List<Dependency> dependencies) {
    this.dependencies = dependencies;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    Project project = (Project) o;
    return groupId.equals(project.groupId) && artifactId.equals(project.artifactId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(groupId, artifactId);
  }
}
