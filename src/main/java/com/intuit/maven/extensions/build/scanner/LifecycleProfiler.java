package com.intuit.maven.extensions.build.scanner;

import static java.lang.System.currentTimeMillis;

import com.intuit.maven.extensions.build.scanner.infra.DataStorage;
import com.intuit.maven.extensions.build.scanner.infra.MongoDataStorage;
import com.intuit.maven.extensions.build.scanner.model.*;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.maven.eventspy.AbstractEventSpy;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.execution.ProjectDependencyGraph;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Named
@Singleton
public class LifecycleProfiler extends AbstractEventSpy {
  private static final Logger LOGGER = LoggerFactory.getLogger(LifecycleProfiler.class.getName());
  private final AtomicInteger threadIndexGenerator = new AtomicInteger();
  private final ThreadLocal<Integer> threadIndex =
      ThreadLocal.withInitial(threadIndexGenerator::incrementAndGet);
  private final boolean enabled;
  private final Function<SessionProfile, DataStorage> dataStorageFactory;
  private DataStorage dataStorage;
  private SessionProfile sessionProfile;
  private long lastCheckPoint = currentTimeMillis();

  public LifecycleProfiler() {
    this("1".equals(System.getenv("MAVEN_BUILD_SCANNER")), MongoDataStorage::new);
  }

  LifecycleProfiler(boolean enabled, Function<SessionProfile, DataStorage> dataStorageFactory) {
    this.enabled = enabled;
    this.dataStorageFactory = dataStorageFactory;
  }

  public DataStorage getDataStorage() {
    return dataStorage;
  }

  private static Project project(MavenProject mavenProject, ExecutionEvent executionEvent) {
    MavenSession session = executionEvent.getSession();
    ProjectDependencyGraph projectDependencyGraph = session.getProjectDependencyGraph();
    List<MavenProject> dependenciesFromGraph = projectDependencyGraph.getUpstreamProjects(mavenProject, false);
    if (dependenciesFromGraph == null) {
      dependenciesFromGraph = Collections.emptyList();
    }
    List<Dependency> deps = dependenciesFromGraph.stream().map(
            dependency -> new Dependency(dependency.getGroupId(),
                    dependency.getArtifactId(),
                    dependency.getVersion())
    ).collect(Collectors.toList());
    return new Project(
        mavenProject.getGroupId(), mavenProject.getArtifactId(), mavenProject.getVersion(), deps);
  }

  @Override
  public void init(Context context) {}

  private static String hostname() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      return null;
    }
  }

  @Override
  public synchronized void onEvent(Object event) {

    if (!enabled) {
      return;
    }

    if (event instanceof ExecutionEvent) {
      ExecutionEvent executionEvent = (ExecutionEvent) event;
      MavenProject mavenProject = executionEvent.getProject();

      switch (executionEvent.getType()) {
        case SessionStarted:
          {
            MavenSession session = executionEvent.getSession();
            // TODO its rather nice having the report based on the first project run, rather than
            // the top-level project
            // as that indicates quite a different build occurring
            Project project = project(mavenProject, executionEvent);
            String tag = getBranch(mavenProject);

            sessionProfile =
                SessionProfile.builder()
                    .id(UUID.randomUUID().toString())
                    .project(project)
                    .hostname(hostname())
                    .username(System.getProperty("user.name"))
                    .command(command(session.getRequest()))
                    .goals(session.getGoals())
                    .branch(tag)
                    .status(Status.PENDING)
                    .build();
            sessionProfile.setStartTime(currentTimeMillis());

            LOGGER.info(
                "Creating Maven build scanner session profile "
                    + sessionProfile.getProject().getId()
                    + "#"
                    + sessionProfile.getId());

            ProjectDependencyGraph projectDependencyGraph = session.getProjectDependencyGraph();
            List<MavenProject> sortedProjects = projectDependencyGraph.getSortedProjects();
            sortedProjects.forEach(
                dependency -> {
                  Project childProject = project(dependency, executionEvent);
                  sessionProfile.addProjectProfile(
                      new ProjectProfile(childProject, Status.PENDING));
                });

            dataStorage = dataStorageFactory.apply(sessionProfile);
            dataStorage.open();
            break;
          }
        case SessionEnded:
          {
            MavenSession session = executionEvent.getSession();
            sessionProfile.setEndTime(currentTimeMillis());

            if (session.getResult().hasExceptions()) {
              sessionProfile.setStatus(Status.FAILED);
            } else {
              sessionProfile.setStatus(Status.SUCCEEDED);
            }

            sessionProfile.getProjectProfiles().forEach(
                projectProfile -> {
                    Dependency blockerDependency = projectProfile.getProject().getDependencies().stream()
                          .map(dependency -> Pair.of(dependency, sessionProfile.getProjectEndTime(
                                  new Project(dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion(), Collections.emptyList()))))
                          .filter(pair -> pair.getRight() != null)
                          .max(Comparator.comparing(Pair::getRight)).orElse(Pair.of(null, null)).getLeft();
                    projectProfile.setBlockerDependency(blockerDependency);
                  }
                );

            dataStorage.close();

            LOGGER.info(
                "Created Maven build scanner session profile "
                    + sessionProfile.getProject().getId()
                    + "#"
                    + sessionProfile.getId());
            LOGGER.info(
                "Open http://localhost:3000/?projectId={}&sessionId={} to view your Maven build scanner results",
                sessionProfile.getProject().getId(),
                sessionProfile.getId());
            break;
          }
        case ProjectStarted:
          {
            sessionProfile
                .getProjectProfile(mavenProject)
                .setStartTime(currentTimeMillis());
            break;
          }
        case ProjectSucceeded:
        case ProjectFailed:
          {
            ProjectProfile projectProfile = sessionProfile.getProjectProfile(mavenProject);
            projectProfile.setStatus(
                Status.valueOf(executionEvent.getType().name().toUpperCase().substring(7)));
            long time = currentTimeMillis();
            projectProfile.setEndTime(time);
            sessionProfile.endProject(projectProfile.getProject(), time);
            sessionProfile.setEndTime(time);
            maybeCheckPoint();
            break;
          }
        case MojoStarted:
          {
            MojoExecution mojoExecution = executionEvent.getMojoExecution();

            ProjectProfile projectProfile = sessionProfile.getProjectProfile(mavenProject);
            MojoProfile mojoProfile =
                new MojoProfile(
                    mojo(mojoExecution),
                    mojoExecution.getExecutionId(),
                    mojoExecution.getGoal(),
                    Status.PENDING,
                    threadIndex.get());
            mojoProfile.setStartTime(currentTimeMillis());

            projectProfile.addMojoProfile(mojoProfile);
            break;
          }
        case MojoSucceeded:
        case MojoFailed:
          {
            MojoExecution mojoExecution = executionEvent.getMojoExecution();
            ProjectProfile projectProfile = sessionProfile.getProjectProfile(mavenProject);

            MojoProfile mojoProfile =
                projectProfile.getMojoProfile(
                    mojo(mojoExecution), mojoExecution.getExecutionId(), mojoExecution.getGoal());
            mojoProfile.setEndTime(currentTimeMillis());
            mojoProfile.setStatus(
                Status.valueOf(executionEvent.getType().name().toUpperCase().substring(4)));
          }
          break;
      }
    }
  }

  private void maybeCheckPoint() {
    if (currentTimeMillis() - lastCheckPoint > 30_0000) {
      LOGGER.info("Requesting check-point");
      dataStorage.checkPoint();
      lastCheckPoint = currentTimeMillis();
    }
  }

  private String getBranch(MavenProject mavenProject) {

    try {
      File basedir = mavenProject.getBasedir();
      File gitHead;
      do {
        gitHead = new File(basedir, ".git/HEAD");
        basedir = basedir.getParentFile();
      } while (basedir != null && !gitHead.exists());

      if (!gitHead.exists()) {
          return "&lt;git not found&gt;";
      }

      //noinspection OptionalGetWithoutIsPresent
      return Files.readAllLines(gitHead.toPath())
          .stream()
          .map(line -> line.replaceFirst(".*/", ""))
          .findFirst()
          .get();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private String command(MavenExecutionRequest request) {
    List<String> out = new ArrayList<>();

    out.add("mvn");

    out.add("-s " + request.getUserSettingsFile());

    out.add("-T " + request.getDegreeOfConcurrency());

    request.getActiveProfiles().stream().map(profile -> "-P" + profile).forEach(out::add);

    request
        .getUserProperties()
        .entrySet()
        .stream()
        .map(entry -> "-D" + entry.getKey() + "=" + entry.getValue())
        .forEach(out::add);

    out.addAll(request.getGoals());

    return String.join(" ", out);
  }

  private Mojo mojo(MojoExecution mojoExecution) {
    Mojo mojo = new Mojo(mojoExecution.getGroupId(), mojoExecution.getArtifactId(), mojoExecution.getVersion());
    return mojo;
  }
}
