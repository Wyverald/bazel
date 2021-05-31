package com.google.devtools.build.lib.bazel.bzlmod;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.FileStateValue;
import com.google.devtools.build.lib.actions.FileValue;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.ServerDirectories;
import com.google.devtools.build.lib.analysis.util.AnalysisMock;
import com.google.devtools.build.lib.bazel.bzlmod.repo.BzlmodRepoRuleValue;
import com.google.devtools.build.lib.bazel.bzlmod.repo.RepoSpecsFunction;
import com.google.devtools.build.lib.bazel.bzlmod.repo.RepoSpecsValue;
import com.google.devtools.build.lib.bazel.repository.starlark.StarlarkRepositoryModule;
import com.google.devtools.build.lib.packages.PackageFactory;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.rules.repository.LocalRepositoryFunction;
import com.google.devtools.build.lib.rules.repository.LocalRepositoryRule;
import com.google.devtools.build.lib.rules.repository.RepositoryDelegatorFunction;
import com.google.devtools.build.lib.rules.repository.RepositoryFunction;
import com.google.devtools.build.lib.skyframe.BazelSkyframeExecutorConstants;
import com.google.devtools.build.lib.skyframe.BzlmodRepoRuleFunction;
import com.google.devtools.build.lib.skyframe.ExternalFilesHelper;
import com.google.devtools.build.lib.skyframe.ExternalFilesHelper.ExternalFileAction;
import com.google.devtools.build.lib.skyframe.FileFunction;
import com.google.devtools.build.lib.skyframe.FileStateFunction;
import com.google.devtools.build.lib.skyframe.ManagedDirectoriesKnowledge;
import com.google.devtools.build.lib.skyframe.PrecomputedFunction;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.starlarkbuildapi.repository.RepositoryBootstrap;
import com.google.devtools.build.lib.testutil.FoundationTestCase;
import com.google.devtools.build.lib.testutil.TestRuleClassProvider;
import com.google.devtools.build.lib.util.io.TimestampGranularityMonitor;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.UnixGlob;
import com.google.devtools.build.skyframe.EvaluationContext;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.InMemoryMemoizingEvaluator;
import com.google.devtools.build.skyframe.MemoizingEvaluator;
import com.google.devtools.build.skyframe.RecordingDifferencer;
import com.google.devtools.build.skyframe.SequencedRecordingDifferencer;
import com.google.devtools.build.skyframe.SequentialBuildDriver;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionName;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.starlark.java.eval.StarlarkSemantics;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class DiscoveryFunctionTest extends FoundationTestCase {

  private Path workspaceRoot;
  private SequentialBuildDriver driver;
  private RecordingDifferencer differencer;
  private EvaluationContext evaluationContext;
  private FakeRegistry.Factory registryFactory;

  @Before
  public void setup() throws Exception {
    workspaceRoot = scratch.dir("/ws");
    differencer = new SequencedRecordingDifferencer();
    evaluationContext = EvaluationContext.newBuilder()
        .setNumThreads(8)
        .setEventHandler(reporter)
        .build();
    registryFactory = new FakeRegistry.Factory();
    AtomicReference<PathPackageLocator> packageLocator = new AtomicReference<>(
        new PathPackageLocator(
            outputBase,
            ImmutableList.of(Root.fromPath(rootDirectory)),
            BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY));
    BlazeDirectories directories =
        new BlazeDirectories(
            new ServerDirectories(rootDirectory, outputBase, rootDirectory),
            rootDirectory,
            /* defaultSystemJavabase= */ null,
            AnalysisMock.get().getProductName());
    ExternalFilesHelper externalFilesHelper =
        ExternalFilesHelper.createForTesting(
            packageLocator,
            ExternalFileAction.DEPEND_ON_EXTERNAL_PKG_FOR_EXTERNAL_REPO_PATHS,
            directories);
    ConfiguredRuleClassProvider.Builder builder = new ConfiguredRuleClassProvider.Builder();
    TestRuleClassProvider.addStandardRules(builder);
    builder
        .clearWorkspaceFileSuffixForTesting()
        .addStarlarkBootstrap(new RepositoryBootstrap(new StarlarkRepositoryModule()));
    ConfiguredRuleClassProvider ruleClassProvider = builder.build();

    PackageFactory packageFactory =
        AnalysisMock.get()
            .getPackageFactoryBuilderForTesting(directories)
            .build(ruleClassProvider, fileSystem);

    ImmutableMap<String, RepositoryFunction> repositoryHandlers =
        ImmutableMap.of(
            LocalRepositoryRule.NAME, (RepositoryFunction) new LocalRepositoryFunction());
    MemoizingEvaluator evaluator = new InMemoryMemoizingEvaluator(
        ImmutableMap.<SkyFunctionName, SkyFunction>builder()
            .put(FileValue.FILE, new FileFunction(packageLocator))
            .put(FileStateValue.FILE_STATE, new FileStateFunction(
                new AtomicReference<TimestampGranularityMonitor>(),
                new AtomicReference<>(UnixGlob.DEFAULT_SYSCALLS),
                externalFilesHelper))
            .put(SkyFunctions.DISCOVERY, new DiscoveryFunction())
            .put(SkyFunctions.MODULE_FILE,
                new ModuleFileFunction(registryFactory, workspaceRoot))
            .put(SkyFunctions.PRECOMPUTED, new PrecomputedFunction())
            .put(
                SkyFunctions.REPOSITORY_DIRECTORY,
                new RepositoryDelegatorFunction(
                    repositoryHandlers,
                    null,
                    new AtomicBoolean(true),
                    ImmutableMap::of,
                    directories,
                    ManagedDirectoriesKnowledge.NO_MANAGED_DIRECTORIES,
                    BazelSkyframeExecutorConstants.EXTERNAL_PACKAGE_HELPER))
            .put(BzlmodRepoRuleValue.BZLMOD_REPO_RULE,
                new BzlmodRepoRuleFunction(packageFactory, ruleClassProvider, directories))
            .put(RepoSpecsValue.REPO_SPECS, new RepoSpecsFunction())
            .build(),
        differencer);
    driver = new SequentialBuildDriver(evaluator);

    PrecomputedValue.STARLARK_SEMANTICS.set(differencer, StarlarkSemantics.DEFAULT);
    RepositoryDelegatorFunction.REPOSITORY_OVERRIDES.set(differencer, ImmutableMap.of());
    RepositoryDelegatorFunction.DEPENDENCY_FOR_UNCONDITIONAL_FETCHING.set(
        differencer, RepositoryDelegatorFunction.DONT_FETCH_UNCONDITIONALLY);
    PrecomputedValue.PATH_PACKAGE_LOCATOR.set(differencer, packageLocator.get());
    RepositoryDelegatorFunction.RESOLVED_FILE_INSTEAD_OF_WORKSPACE.set(
        differencer, Optional.empty());
    PrecomputedValue.REPO_ENV.set(differencer, ImmutableMap.of());
    RepositoryDelegatorFunction.OUTPUT_VERIFICATION_REPOSITORY_RULES.set(
        differencer, ImmutableSet.of());
    RepositoryDelegatorFunction.RESOLVED_FILE_FOR_VERIFICATION.set(differencer, Optional.empty());
  }

  @Test
  public void testSimpleDiamond() throws Exception {
    scratch.file(workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "module(name='A',version='0.1')",
        "bazel_dep(name='B',version='1.0')",
        "bazel_dep(name='C',version='2.0')");
    FakeRegistry registry = registryFactory.newFakeRegistry()
        .addModule(ModuleKey.create("B", "1.0"),
            "module(name='B', version='1.0');bazel_dep(name='D',version='3.0')",
            LocalPathOverride.create("B").getRepoSpec("B"))
        .addModule(ModuleKey.create("C", "2.0"),
            "module(name='C', version='2.0');bazel_dep(name='D',version='3.0')",
            LocalPathOverride.create("C").getRepoSpec("C"))
        .addModule(ModuleKey.create("D", "3.0"),
            "module(name='D', version='3.0')",
            LocalPathOverride.create("D").getRepoSpec("D"));
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableList.of(registry.getUrl()));

    EvaluationResult<DiscoveryValue> result =
        driver.evaluate(ImmutableList.of(DiscoveryValue.KEY), evaluationContext);
    if (result.hasError()) {
      fail(result.getError().toString());
    }
    DiscoveryValue discoveryValue = result.get(DiscoveryValue.KEY);
    assertThat(discoveryValue.getRootModuleName()).isEqualTo("A");
    assertThat(discoveryValue.getDepGraph().get(ModuleKey.create("A", ""))).isEqualTo(
        Module.builder()
            .setName("A")
            .setVersion("0.1")
            .addDep("B", ModuleKey.create("B", "1.0"))
            .addDep("C", ModuleKey.create("C", "2.0"))
            .build());
    assertThat(discoveryValue.getDepGraph().get(ModuleKey.create("B", "1.0"))).isEqualTo(
        Module.builder()
            .setName("B")
            .setVersion("1.0")
            .addDep("D", ModuleKey.create("D", "3.0"))
            .setRegistry(registry)
            .build());
    assertThat(discoveryValue.getDepGraph().get(ModuleKey.create("C", "2.0"))).isEqualTo(
        Module.builder()
            .setName("C")
            .setVersion("2.0")
            .addDep("D", ModuleKey.create("D", "3.0"))
            .setRegistry(registry)
            .build());
    assertThat(discoveryValue.getDepGraph().get(ModuleKey.create("D", "3.0"))).isEqualTo(
        Module.builder()
            .setName("D")
            .setVersion("3.0")
            .setRegistry(registry)
            .build());
    assertThat(discoveryValue.getDepGraph()).hasSize(4);
    assertThat(discoveryValue.getOverrides())
        .containsExactly("A", LocalPathOverride.create(""));
  }

  @Test
  public void testSingleVersionOverride() throws Exception {
    scratch.file(workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "module(name='A',version='0.1')",
        "bazel_dep(name='B',version='0.1')",
        "override_dep(name='C',override=single_version_override(version='2.0'))");
    FakeRegistry registry = registryFactory.newFakeRegistry()
        .addModule(ModuleKey.create("B", "0.1"),
            "module(name='B', version='0.1');bazel_dep(name='C',version='1.0')",
            LocalPathOverride.create("B").getRepoSpec("B"))
        .addModule(ModuleKey.create("C", "1.0"),
            "module(name='C', version='1.0');",
            LocalPathOverride.create("C").getRepoSpec("C"))
        .addModule(ModuleKey.create("C", "2.0"),
            "module(name='C', version='2.0');",
            LocalPathOverride.create("C").getRepoSpec("C"));
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableList.of(registry.getUrl()));

    EvaluationResult<DiscoveryValue> result =
        driver.evaluate(ImmutableList.of(DiscoveryValue.KEY), evaluationContext);
    if (result.hasError()) {
      fail(result.getError().toString());
    }
    DiscoveryValue discoveryValue = result.get(DiscoveryValue.KEY);
    assertThat(discoveryValue.getRootModuleName()).isEqualTo("A");
    assertThat(discoveryValue.getDepGraph().get(ModuleKey.create("A", ""))).isEqualTo(
        Module.builder()
            .setName("A")
            .setVersion("0.1")
            .addDep("B", ModuleKey.create("B", "0.1"))
            .build());
    assertThat(discoveryValue.getDepGraph().get(ModuleKey.create("B", "0.1"))).isEqualTo(
        Module.builder()
            .setName("B")
            .setVersion("0.1")
            .addDep("C", ModuleKey.create("C", "2.0"))
            .setRegistry(registry)
            .build());
    assertThat(discoveryValue.getDepGraph().get(ModuleKey.create("C", "2.0"))).isEqualTo(
        Module.builder()
            .setName("C")
            .setVersion("2.0")
            .setRegistry(registry)
            .build());
    assertThat(discoveryValue.getDepGraph()).hasSize(3);
    assertThat(discoveryValue.getOverrides()).containsExactly(
        "A", LocalPathOverride.create(""),
        "C", SingleVersionOverride.create("2.0", "", ImmutableList.of(), 0));
  }

  @Test
  public void testRegistryOverride() throws Exception {
    FakeRegistry registry1 = registryFactory.newFakeRegistry()
        .addModule(ModuleKey.create("B", "0.1"),
            "module(name='B', version='0.1');bazel_dep(name='C',version='1.0')",
            LocalPathOverride.create("B").getRepoSpec("B"))
        .addModule(ModuleKey.create("C", "1.0"),
            "module(name='C', version='1.0');",
            LocalPathOverride.create("C").getRepoSpec("C"));
    FakeRegistry registry2 = registryFactory.newFakeRegistry()
        .addModule(ModuleKey.create("C", "1.0"),
            "module(name='C', version='1.0');bazel_dep(name='B',version='0.1')",
            LocalPathOverride.create("C").getRepoSpec("C"));
    scratch.file(workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "module(name='A',version='0.1')",
        "bazel_dep(name='B',version='0.1')",
        "override_dep(name='C',override=single_version_override(registry='" + registry2.getUrl()
            + "'))");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableList.of(registry1.getUrl()));

    EvaluationResult<DiscoveryValue> result =
        driver.evaluate(ImmutableList.of(DiscoveryValue.KEY), evaluationContext);
    if (result.hasError()) {
      fail(result.getError().toString());
    }
    DiscoveryValue discoveryValue = result.get(DiscoveryValue.KEY);
    assertThat(discoveryValue.getRootModuleName()).isEqualTo("A");
    assertThat(discoveryValue.getDepGraph().get(ModuleKey.create("A", ""))).isEqualTo(
        Module.builder()
            .setName("A")
            .setVersion("0.1")
            .addDep("B", ModuleKey.create("B", "0.1"))
            .build());
    assertThat(discoveryValue.getDepGraph().get(ModuleKey.create("B", "0.1"))).isEqualTo(
        Module.builder()
            .setName("B")
            .setVersion("0.1")
            .addDep("C", ModuleKey.create("C", "1.0"))
            .setRegistry(registry1)
            .build());
    assertThat(discoveryValue.getDepGraph().get(ModuleKey.create("C", "1.0"))).isEqualTo(
        Module.builder()
            .setName("C")
            .setVersion("1.0")
            .addDep("B", ModuleKey.create("B", "0.1"))
            .setRegistry(registry2)
            .build());
    assertThat(discoveryValue.getDepGraph()).hasSize(3);
    assertThat(discoveryValue.getOverrides()).containsExactly(
        "A", LocalPathOverride.create(""),
        "C", SingleVersionOverride.create("", registry2.getUrl(), ImmutableList.of(), 0));
  }

  @Test
  public void testLocalPathOverride() throws Exception {
    Path pathToC = scratch.dir("/pathToC");
    scratch.file(pathToC.getRelative("MODULE.bazel").getPathString(),
        "module(name='C',version='2.0')");
    scratch.file(pathToC.getRelative("WORKSPACE").getPathString());
    scratch.file(workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "module(name='A',version='0.1')",
        "bazel_dep(name='B',version='0.1')",
        "override_dep(name='C',override=local_path_override(path='" + pathToC.getPathString()
            + "'))");
    FakeRegistry registry = registryFactory.newFakeRegistry()
        .addModule(ModuleKey.create("B", "0.1"),
            "module(name='B', version='0.1');bazel_dep(name='C',version='1.0')",
            LocalPathOverride.create("B").getRepoSpec("B"))
        .addModule(ModuleKey.create("C", "1.0"),
            "module(name='C', version='1.0');",
            LocalPathOverride.create("C").getRepoSpec("C"));
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableList.of(registry.getUrl()));

    EvaluationResult<DiscoveryValue> result =
        driver.evaluate(ImmutableList.of(DiscoveryValue.KEY), evaluationContext);
    if (result.hasError()) {
      fail(result.getError().toString());
    }
    DiscoveryValue discoveryValue = result.get(DiscoveryValue.KEY);
    assertThat(discoveryValue.getRootModuleName()).isEqualTo("A");
    assertThat(discoveryValue.getDepGraph().get(ModuleKey.create("A", ""))).isEqualTo(
        Module.builder()
            .setName("A")
            .setVersion("0.1")
            .addDep("B", ModuleKey.create("B", "0.1"))
            .build());
    assertThat(discoveryValue.getDepGraph().get(ModuleKey.create("B", "0.1"))).isEqualTo(
        Module.builder()
            .setName("B")
            .setVersion("0.1")
            .addDep("C", ModuleKey.create("C", ""))
            .setRegistry(registry)
            .build());
    assertThat(discoveryValue.getDepGraph().get(ModuleKey.create("C", ""))).isEqualTo(
        Module.builder()
            .setName("C")
            .setVersion("2.0")
            .build());
    assertThat(discoveryValue.getDepGraph()).hasSize(3);
    assertThat(discoveryValue.getOverrides()).containsExactly(
        "A", LocalPathOverride.create(""),
        "C", LocalPathOverride.create(pathToC.getPathString()));
  }

}
