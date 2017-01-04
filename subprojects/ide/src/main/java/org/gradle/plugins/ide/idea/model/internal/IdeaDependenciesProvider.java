/*
 * Copyright 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.plugins.ide.idea.model.internal;

import com.google.common.base.Function;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Sets;
import org.gradle.api.Nullable;
import org.gradle.api.Project;
import org.gradle.api.Transformer;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.composite.internal.CompositeBuildIdeProjectResolver;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.plugins.ide.idea.model.Dependency;
import org.gradle.plugins.ide.idea.model.FilePath;
import org.gradle.plugins.ide.idea.model.IdeaModule;
import org.gradle.plugins.ide.idea.model.SingleEntryModuleLibrary;
import org.gradle.plugins.ide.internal.IdeDependenciesExtractor;
import org.gradle.plugins.ide.internal.resolver.model.IdeDependencyKey;
import org.gradle.plugins.ide.internal.resolver.model.IdeExtendedRepoFileDependency;
import org.gradle.plugins.ide.internal.resolver.model.IdeLocalFileDependency;
import org.gradle.plugins.ide.internal.resolver.model.IdeProjectDependency;
import org.gradle.plugins.ide.internal.resolver.model.UnresolvedIdeRepoFileDependency;
import org.gradle.util.CollectionUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class IdeaDependenciesProvider {


    private static final String MINUS = "minus";
    private static final String PLUS = "plus";

    private final IdeDependenciesExtractor dependenciesExtractor;
    private final ModuleDependencyBuilder moduleDependencyBuilder;
    private Transformer<FilePath, File> getPath;

    public IdeaDependenciesProvider(ServiceRegistry serviceRegistry) {
        this(new IdeDependenciesExtractor(), serviceRegistry);
    }

    IdeaDependenciesProvider(IdeDependenciesExtractor dependenciesExtractor, ServiceRegistry serviceRegistry) {
        this.dependenciesExtractor = dependenciesExtractor;
        moduleDependencyBuilder = new ModuleDependencyBuilder(CompositeBuildIdeProjectResolver.from(serviceRegistry));
    }

    public Set<Dependency> provide(final IdeaModule ideaModule) {
        getPath = new Transformer<FilePath, File>() {
            @Override
            @Nullable
            public FilePath transform(File file) {
                return file != null ? ideaModule.getPathFactory().path(file) : null;
            }
        };
        Set<Configuration> ideaConfigurations = ideaConfigurations(ideaModule);
        Set<Dependency> result = new LinkedHashSet<Dependency>() {
            @Override
            public String toString() {
                Multimap<String, Dependency> dependencies = Multimaps.index(this, new Function<Dependency, String>() {
                    @Override
                    public String apply(Dependency input) {
                        return input.getScope();
                    }
                });
                StringBuilder sb = new StringBuilder();
                for (String key : dependencies.keySet()) {
                    sb.append(key).append(": ");
                    sb.append(dependencies.get(key)).append("\n");
                }
                return sb.toString();
            }
        };
        if (ideaModule.getSingleEntryLibraries() != null) {
            for (Map.Entry<String, Iterable<File>> singleEntryLibrary : ideaModule.getSingleEntryLibraries().entrySet()) {
                String scope = singleEntryLibrary.getKey();
                for (File file : singleEntryLibrary.getValue()) {
                    if (file != null && file.isDirectory()) {
                        result.add(new SingleEntryModuleLibrary(getPath.transform(file), scope));
                    }
                }
            }
        }
        result.addAll(provideFromScopeRuleMappings(ideaModule, ideaConfigurations));
        return result;
    }

    public Collection<UnresolvedIdeRepoFileDependency> getUnresolvedDependencies(final IdeaModule ideaModule) {
        final ConfigurationContainer configurations = ideaModule.getProject().getConfigurations();
        Set<UnresolvedIdeRepoFileDependency> usedUnresolvedDependencies = Sets.newTreeSet(new Comparator<UnresolvedIdeRepoFileDependency>() {
            @Override
            public int compare(UnresolvedIdeRepoFileDependency left, UnresolvedIdeRepoFileDependency right) {
                return left.getDisplayName().compareTo(right.getDisplayName());
            }
        });
        for (GeneratedIdeaScope scope : GeneratedIdeaScope.values()) {
            if (shouldProcessScope(scope)) {
                Map<String, Collection<Configuration>> plusMinusConfigurations = ideaModule.getScopes().get(scope.name());
                if (plusMinusConfigurations == null) {
                    plusMinusConfigurations = Maps.newHashMap();
                }

                Collection<Configuration> minusConfigurations = minusConfigurations(ideaModule.getProject().getConfigurations(), scope, plusMinusConfigurations.get(MINUS));
                Collection<Configuration> plusConfigurations = plusConfigurations(configurations, scope, plusMinusConfigurations.get(PLUS));

                usedUnresolvedDependencies.addAll(dependenciesExtractor.unresolvedExternalDependencies(plusConfigurations, minusConfigurations));
            }
        }

        return usedUnresolvedDependencies;
    }

    private Set<Dependency> provideFromScopeRuleMappings(IdeaModule ideaModule, Collection<Configuration> ideaConfigurations) {
        Multimap<IdeDependencyKey<?, Dependency>, Configuration> dependencyToConfigurations = LinkedHashMultimap.create();
        Project project = ideaModule.getProject();
        ConfigurationContainer configurations = project.getConfigurations();
        for (Configuration configuration : ideaConfigurations) {
            // project dependencies
            Collection<IdeProjectDependency> ideProjectDependencies = dependenciesExtractor.extractProjectDependencies(
                project, Collections.singletonList(configuration), Collections.<Configuration>emptyList());
            for (IdeProjectDependency ideProjectDependency : ideProjectDependencies) {
                IdeDependencyKey<?, Dependency> key = IdeDependencyKey.forProjectDependency(
                    ideProjectDependency,
                    new IdeDependencyKey.DependencyBuilder<IdeProjectDependency, Dependency>() {
                        @Override
                        public Dependency buildDependency(IdeProjectDependency dependency, String scope) {
                            return moduleDependencyBuilder.create(dependency, scope);
                        }
                    });
                dependencyToConfigurations.put(key, configuration);
            }
            // repository dependencies
            if (!ideaModule.isOffline()) {
                Collection<IdeExtendedRepoFileDependency> ideRepoFileDependencies = dependenciesExtractor.extractRepoFileDependencies(
                        ideaModule.getProject().getDependencies(), Collections.singletonList(configuration), Collections.<Configuration>emptyList(),
                        ideaModule.isDownloadSources(), ideaModule.isDownloadJavadoc());
                for (IdeExtendedRepoFileDependency ideRepoFileDependency : ideRepoFileDependencies) {
                    IdeDependencyKey<?, Dependency> key = IdeDependencyKey.forRepoFileDependency(
                        ideRepoFileDependency,
                        new IdeDependencyKey.DependencyBuilder<IdeExtendedRepoFileDependency, Dependency>() {
                            @Override
                            public Dependency buildDependency(IdeExtendedRepoFileDependency dependency, String scope) {
                                Set<FilePath> javadoc = CollectionUtils.collect(dependency.getJavadocFiles(), new LinkedHashSet<FilePath>(), getPath);
                                Set<FilePath> source = CollectionUtils.collect(dependency.getSourceFiles(), new LinkedHashSet<FilePath>(), getPath);
                                SingleEntryModuleLibrary library = new SingleEntryModuleLibrary(
                                    getPath.transform(dependency.getFile()), javadoc, source, scope);
                                library.setModuleVersion(dependency.getId());
                                return library;
                            }
                        });
                    dependencyToConfigurations.put(key, configuration);
                }
            }
            // file dependencies
            Collection<IdeLocalFileDependency> ideLocalFileDependencies = dependenciesExtractor.extractLocalFileDependencies(
                Collections.singletonList(configuration), Collections.<Configuration>emptyList());
            for (IdeLocalFileDependency fileDependency : ideLocalFileDependencies) {
                IdeDependencyKey<?, Dependency> key = IdeDependencyKey.forLocalFileDependency(
                    fileDependency,
                    new IdeDependencyKey.DependencyBuilder<IdeLocalFileDependency, Dependency>() {
                        @Override
                        public Dependency buildDependency(IdeLocalFileDependency dependency, String scope) {
                            return new SingleEntryModuleLibrary(getPath.transform(dependency.getFile()), scope);
                        }
                    });
                dependencyToConfigurations.put(key, configuration);
            }
        }

        Multimap<IdeDependencyKey<?, Dependency>, GeneratedIdeaScope> mapping = HashMultimap.create();
        for (GeneratedIdeaScope scope : GeneratedIdeaScope.values()) {
            if (shouldProcessScope(scope)) {
                Map<String, Collection<Configuration>> plusMinusConfigurations = ideaModule.getScopes().get(scope.name());
                if (plusMinusConfigurations == null) {
                    plusMinusConfigurations = Maps.newHashMap();
                }

                Collection<Configuration> minusConfigurations = minusConfigurations(configurations, scope, plusMinusConfigurations.get(MINUS));
                Collection<Configuration> plusConfigurations = plusConfigurations(configurations, scope, plusMinusConfigurations.get(PLUS));

                Collection<IdeDependencyKey<?, Dependency>> matchingDependencies =
                    extractDependencies(dependencyToConfigurations, plusConfigurations, minusConfigurations);

                for (IdeDependencyKey<?, Dependency> dependencyKey : matchingDependencies) {
                    mapping.put(dependencyKey, scope);
                }
            }
        }
        Set<IdeDependencyKey<?, Dependency>> keys = mapping.keySet();
        ImmutableSet.Builder<Dependency> builder = new ImmutableSet.Builder<Dependency>();
        for (IdeDependencyKey<?, Dependency> key : keys) {
            Collection<GeneratedIdeaScope> generatedIdeaScopes = mapping.get(key);
            if (generatedIdeaScopes.size()==1) {
                Set<String> scopes = Iterables.getLast(generatedIdeaScopes).scopes;
                for (String scope : scopes) {
                    // There should be only one
                    builder.add(key.buildDependency(scope));
                }
            } else {
                Set<GeneratedIdeaScope> generatedScopes = new HashSet<GeneratedIdeaScope>(generatedIdeaScopes);
                prefer(generatedScopes, GeneratedIdeaScope.PROVIDED, GeneratedIdeaScope.COMPILE);
                prefer(generatedScopes, GeneratedIdeaScope.COMPILE, GeneratedIdeaScope.RUNTIME);
                for (GeneratedIdeaScope generatedScope : generatedScopes) {
                    for (String scope : generatedScope.scopes) {
                        // There should be only one
                        builder.add(key.buildDependency(scope));
                    }
                }
            }
        }

        return builder.build();
    }

    private static void prefer(Set<GeneratedIdeaScope> scopes, GeneratedIdeaScope scope, GeneratedIdeaScope over) {
        if (scopes.contains(scope) && scopes.contains(over)) {
            scopes.remove(over);
        }
    }

    private Collection<Configuration> plusConfigurations(ConfigurationContainer configurations, GeneratedIdeaScope scope, Collection<Configuration> plusConfigurations) {
        Set<Configuration> result = Sets.newLinkedHashSet();
        switch (scope) {
            case COMPILE:
                addConfigurationIfExists(configurations, result, JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME);
                break;
            case RUNTIME:
                addConfigurationIfExists(configurations, result, JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME);
                break;
            case TEST:
                addConfigurationIfExists(configurations, result, JavaPlugin.TEST_COMPILE_CLASSPATH_CONFIGURATION_NAME);
                addConfigurationIfExists(configurations, result, JavaPlugin.TEST_RUNTIME_CLASSPATH_CONFIGURATION_NAME);
                break;
            case PROVIDED:
                addConfigurationIfExists(configurations, result, JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME);
                break;
        }
        if (plusConfigurations != null) {
            Iterables.addAll(result, plusConfigurations);
        }
        return result;
    }

    private Collection<Configuration> minusConfigurations(ConfigurationContainer configurations, GeneratedIdeaScope scope, Collection<Configuration> minusConfigurations) {
        Set<Configuration> result = Sets.newLinkedHashSet();
        switch (scope) {
            case COMPILE:
                //addConfigurationIfExists(configurations, result, JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME);
                break;
            case RUNTIME:
                addConfigurationIfExists(configurations, result, JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME);
                break;
            case TEST:
                addConfigurationIfExists(configurations, result, JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME);
                break;
            case PROVIDED:
                addConfigurationIfExists(configurations, result, JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME);
                addConfigurationIfExists(configurations, result, JavaPlugin.API_ELEMENTS_CONFIGURATION_NAME);
                break;
        }
        if (minusConfigurations != null) {
            Iterables.addAll(result, minusConfigurations);
        }
        return result;
    }

    private void addConfigurationIfExists(ConfigurationContainer configurations, Set<Configuration> result, String implementationConfigurationName) {
        addConfigurationIfExists(configurations, result, implementationConfigurationName, true);
    }

    private void addConfigurationIfExists(ConfigurationContainer configurations, Set<Configuration> result, String implementationConfigurationName, boolean hierarchy) {
        Configuration byName = configurations.findByName(implementationConfigurationName);
        if (byName != null) {
            if (hierarchy) {
                result.add(byName);
            } else {
                result.add(byName.copy());
            }
        }
    }

    private boolean shouldProcessScope(GeneratedIdeaScope scope) {
        return !scope.composite;
    }

    private static Function<String, Dependency> scopeToDependency(final IdeDependencyKey<?, Dependency> dependencyKey) {
        return new Function<String, Dependency>() {
            @Override
            @Nullable
            public Dependency apply(String s) {
                return dependencyKey.buildDependency(s);
            }
        };
    }

    private Set<Configuration> ideaConfigurations(final IdeaModule ideaModule) {
        ConfigurationContainer configurationContainer = ideaModule.getProject().getConfigurations();
        Set<Configuration> configurations = Sets.newLinkedHashSet();
        for (Map<String, Collection<Configuration>> scopeMap : ideaModule.getScopes().values()) {
            for (Configuration cfg : Iterables.concat(scopeMap.values())) {
                configurations.add(cfg);
            }
        }
        for (GeneratedIdeaScope generatedIdeaScope : GeneratedIdeaScope.values()) {
            configurations.addAll(plusConfigurations(configurationContainer, generatedIdeaScope, null));
            configurations.addAll(minusConfigurations(configurationContainer, generatedIdeaScope, null));
        }
        return configurations;
    }

    /**
     * Looks for dependencies contained in all configurations to remove them from multimap and return as result.
     */
    private List<IdeDependencyKey<?, Dependency>> extractDependencies(Multimap<IdeDependencyKey<?, Dependency>, Configuration> dependenciesToConfigs,
                                                                      Collection<Configuration> plusConfigurations, Collection<Configuration> minusConfigurations) {
        List<IdeDependencyKey<?, Dependency>> deps = new ArrayList<IdeDependencyKey<?, Dependency>>();
        for (IdeDependencyKey<?, Dependency> dependencyKey : dependenciesToConfigs.keySet()) {
            for (Configuration plusConfiguration : plusConfigurations) {
                Collection<Configuration> configurations = dependenciesToConfigs.get(dependencyKey);
                if (configurations.contains(plusConfiguration)) {
                    boolean isInMinus = false;
                    for (Configuration minusConfiguration : minusConfigurations) {
                        if (configurations.contains(minusConfiguration)) {
                            isInMinus = true;
                            break;
                        }
                    }
                    if (!isInMinus) {
                        deps.add(dependencyKey);
                    }
                }
            }

        }
        return deps;
    }
}
