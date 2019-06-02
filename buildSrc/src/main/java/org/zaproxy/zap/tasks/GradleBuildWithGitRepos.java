/*
 * Zed Attack Proxy (ZAP) and its related class files.
 *
 * ZAP is an HTTP/HTTPS proxy for assessing web application security.
 *
 * Copyright 2019 The ZAP Development Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.zaproxy.zap.tasks;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.apache.tools.ant.taskdefs.condition.Os;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.gradle.api.Action;
import org.gradle.api.DefaultTask;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.Named;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Project;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.TaskAction;
import org.zaproxy.zap.internal.RepoData;

/** A task that clones Git repositories and runs Gradle tasks contained in them. */
public class GradleBuildWithGitRepos extends DefaultTask {

    private final RegularFileProperty repositoriesDataFile;
    private final DirectoryProperty repositoriesDirectory;
    private final Property<Boolean> cloneRepositories;
    private final Property<Boolean> updateRepositories;
    private NamedDomainObjectContainer<Task> tasks;

    public GradleBuildWithGitRepos() {
        ObjectFactory objects = getProject().getObjects();
        this.repositoriesDataFile = objects.fileProperty();
        this.repositoriesDirectory = objects.directoryProperty();
        this.cloneRepositories = objects.property(Boolean.class).value(true);
        this.updateRepositories = objects.property(Boolean.class).value(true);
        this.tasks = getProject().container(Task.class, name -> new Task(name, getProject()));
    }

    @InputFile
    public RegularFileProperty getRepositoriesDataFile() {
        return repositoriesDataFile;
    }

    @Input
    public DirectoryProperty getRepositoriesDirectory() {
        return repositoriesDirectory;
    }

    @Input
    public Property<Boolean> getCloneRepositories() {
        return cloneRepositories;
    }

    @Input
    public Property<Boolean> getUpdateRepositories() {
        return updateRepositories;
    }

    @Nested
    public Iterable<Task> getTasks() {
        return new ArrayList<>(tasks);
    }

    public void setTasks(NamedDomainObjectContainer<Task> tasks) {
        this.tasks = tasks;
    }

    public void tasks(Action<? super NamedDomainObjectContainer<Task>> action) {
        action.execute(tasks);
    }

    @TaskAction
    public void buildWeeklyAddOns() throws GitAPIException, IOException {
        Path reposDir = repositoriesDirectory.get().getAsFile().toPath();

        List<RepoData> reposData = readRepoData();
        for (RepoData repoData : reposData) {

            String cloneUrl = repoData.getCloneUrl();
            String repoName = extractRepoName(cloneUrl);
            Path repoDir = reposDir.resolve(repoName);

            if (Files.notExists(repoDir)) {
                if (!cloneRepositories.get()) {
                    getLogger()
                            .warn(
                                    "The directory {} does not exist, the {} add-on(s) will not be built.",
                                    repoDir,
                                    repoName);
                    continue;
                }

                getProject().mkdir(reposDir);

                // XXX Rely just on JGit once it supports depth arg:
                // https://bugs.eclipse.org/bugs/show_bug.cgi?id=475615
                getProject()
                        .exec(
                                spec -> {
                                    spec.setWorkingDir(reposDir);
                                    spec.setExecutable("git");
                                    spec.setEnvironment(Collections.emptyMap());
                                    spec.args(
                                            "clone",
                                            "--branch",
                                            repoData.getBranch(),
                                            "--depth",
                                            "1",
                                            cloneUrl,
                                            repoName);
                                })
                        .assertNormalExitValue();
                getLogger().debug("Cloned {} into {}", cloneUrl, repoDir);
            } else if (updateRepositories.get()) {
                FileRepositoryBuilder builder = new FileRepositoryBuilder();
                Repository repository = builder.setGitDir(repoDir.resolve(".git").toFile()).build();
                try (Git git = new Git(repository)) {
                    git.pull().call();
                    getLogger().debug("Pulled from {} into {}", cloneUrl, repoDir);
                }
            }

            if (repoData.getProjects() == null || repoData.getProjects().isEmpty()) {
                runTasks(repoDir, Arrays.asList(""));
            } else {
                runTasks(repoDir, repoData.getProjects());
            }
        }
    }

    private void runTasks(Path repoDir, List<String> projects) {
        List<String> execArgs = new ArrayList<>();
        for (String project : projects) {
            String taskPrefix = project + ":";
            for (Task task : tasks) {
                execArgs.add(taskPrefix + task.getName());
                execArgs.addAll(task.getArgs().get());
            }
        }
        getProject()
                .exec(
                        spec -> {
                            spec.setWorkingDir(repoDir);
                            spec.setExecutable(gradleWrapper());
                            spec.args(execArgs);
                        })
                .assertNormalExitValue();
    }

    private List<RepoData> readRepoData() throws IOException {
        Gson gson = new Gson();
        Type reposType = new TypeToken<List<RepoData>>() {}.getType();
        File file = repositoriesDataFile.get().getAsFile();
        try (Reader reader = Files.newBufferedReader(file.toPath())) {
            return gson.fromJson(reader, reposType);
        }
    }

    private static String extractRepoName(String cloneUrl) {
        int slashIdx = cloneUrl.lastIndexOf('/');
        if (slashIdx == -1) {
            throw new InvalidUserDataException(
                    "Unable to extract repository name, the clone URL does not have a slash: "
                            + cloneUrl);
        }
        if (!cloneUrl.endsWith(".git")) {
            throw new InvalidUserDataException(
                    "Unable to extract repository name, the clone URL does not end with \".git\": "
                            + cloneUrl);
        }
        return cloneUrl.substring(slashIdx + 1, cloneUrl.indexOf(".git"));
    }

    private static String gradleWrapper() {
        if (Os.isFamily(Os.FAMILY_UNIX)) {
            return "./gradlew";
        }
        return "gradlew.bat";
    }

    public static final class Task implements Named {

        private final String name;
        private final ListProperty<String> args;

        public Task(String name, Project project) {
            this.name = name;
            this.args = project.getObjects().listProperty(String.class);
        }

        @Input
        @Override
        public String getName() {
            return name;
        }

        @Input
        public ListProperty<String> getArgs() {
            return args;
        }
    }
}
