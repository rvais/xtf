package cz.xtf.core.openshift;

import cz.xtf.core.config.WaitingConfig;
import cz.xtf.core.waiting.SimpleWaiter;
import cz.xtf.core.waiting.Waiter;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Endpoints;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.HorizontalPodAutoscaler;
import io.fabric8.kubernetes.api.model.KubernetesList;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.ObjectReference;
import io.fabric8.kubernetes.api.model.ObjectReferenceBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.ReplicationController;
import io.fabric8.kubernetes.api.model.ResourceQuota;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceAccount;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.LocalPortForward;
import io.fabric8.kubernetes.client.dsl.LogWatch;
import io.fabric8.openshift.api.model.Build;
import io.fabric8.openshift.api.model.BuildConfig;
import io.fabric8.openshift.api.model.BuildRequest;
import io.fabric8.openshift.api.model.BuildRequestBuilder;
import io.fabric8.openshift.api.model.DeploymentConfig;
import io.fabric8.openshift.api.model.ImageStream;
import io.fabric8.openshift.api.model.Project;
import io.fabric8.openshift.api.model.ProjectRequest;
import io.fabric8.openshift.api.model.ProjectRequestBuilder;
import io.fabric8.openshift.api.model.OpenshiftRole;
import io.fabric8.openshift.api.model.OpenshiftRoleBinding;
import io.fabric8.openshift.api.model.OpenshiftRoleBindingBuilder;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.api.model.RouteSpecBuilder;
import io.fabric8.openshift.api.model.Template;
import io.fabric8.openshift.client.DefaultOpenShiftClient;
import io.fabric8.openshift.client.OpenShiftConfig;
import io.fabric8.openshift.client.OpenShiftConfigBuilder;
import io.fabric8.openshift.client.ParameterValue;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import rx.Observable;
import rx.observables.StringObservable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

@Slf4j
public class OpenShift extends DefaultOpenShiftClient {
	private static String routeSuffix;

	public static final String KEEP_LABEL = "xtf.cz/keep";

	/**
	 * Autoconfigures the client with the default fabric8 client rules
	 * @return
	 */
	public static OpenShift get(String namespace) {
		Config kubeconfig = Config.autoConfigure(null);

		OpenShiftConfig openShiftConfig = new OpenShiftConfig(kubeconfig);

		setupTimeouts(openShiftConfig);

		if (StringUtils.isNotEmpty(namespace)) {
			openShiftConfig.setNamespace(namespace);
		}

		return new OpenShift(openShiftConfig);
	}

	public static OpenShift get(Path kubeconfigPath, String namespace) {
		try {
			String kubeconfigContents = new String(Files.readAllBytes(kubeconfigPath), StandardCharsets.UTF_8);
			Config kubeconfig = Config.fromKubeconfig(null, kubeconfigContents, kubeconfigPath.toAbsolutePath().toString());
			OpenShiftConfig openShiftConfig = new OpenShiftConfig(kubeconfig);

			setupTimeouts(openShiftConfig);

			if (StringUtils.isNotEmpty(namespace)) {
				openShiftConfig.setNamespace(namespace);
			}

			return new OpenShift(openShiftConfig);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public static OpenShift get(String masterUrl, String namespace, String username, String password) {
		OpenShiftConfig openShiftConfig = new OpenShiftConfigBuilder()
				.withMasterUrl(masterUrl)
				.withTrustCerts(true)
				.withNamespace(namespace)
				.withUsername(username)
				.withPassword(password)
				.build();

		setupTimeouts(openShiftConfig);

		return new OpenShift(openShiftConfig);
	}

	public static OpenShift get(String masterUrl, String namespace, String token) {
		OpenShiftConfig openShiftConfig = new OpenShiftConfigBuilder()
				.withMasterUrl(masterUrl)
				.withTrustCerts(true)
				.withNamespace(namespace)
				.withOauthToken(token)
				.build();

		setupTimeouts(openShiftConfig);

		return new OpenShift(openShiftConfig);
	}

	private static void setupTimeouts(OpenShiftConfig config) {
		config.setBuildTimeout(10 * 60 * 1000);
		config.setRequestTimeout(120_000);
		config.setConnectionTimeout(120_000);
	}

	private final OpenShiftWaiters waiters;

	public OpenShift(OpenShiftConfig openShiftConfig) {
		super(openShiftConfig);

		this.waiters = new OpenShiftWaiters(this);
	}

	// General functions
	public KubernetesList createResources(HasMetadata... resources) {
		return createResources(Arrays.asList(resources));
	}

	public KubernetesList createResources(List<HasMetadata> resources) {
		KubernetesList list = new KubernetesList();
		list.setItems(resources);
		return createResources(list);
	}

	public KubernetesList createResources(KubernetesList resources) {
		return lists().create(resources);
	}

	public boolean deleteResources(KubernetesList resources) {
		return lists().delete(resources);
	}

	public void loadResource(InputStream is) {
		load(is).deletingExisting().createOrReplace();
	}

	// Projects
	public ProjectRequest createProjectRequest() {
		return createProjectRequest(new ProjectRequestBuilder().withNewMetadata().withName(getNamespace()).endMetadata().build());
	}

	public ProjectRequest createProjectRequest(String name) {
		return createProjectRequest(new ProjectRequestBuilder().withNewMetadata().withName(name).endMetadata().build());
	}

	public ProjectRequest createProjectRequest(ProjectRequest projectRequest) {
		return projectrequests().create(projectRequest);
	}

	/**
	 * Calls rectreateProject(namespace).
	 *
	 * @see OpenShift#recreateProject(String)
	 */
	public ProjectRequest recreateProject() {
		return recreateProject(new ProjectRequestBuilder().withNewMetadata().withName(getNamespace()).endMetadata().build());
	}

	/**
	 * Creates or recreates project specified by name.
	 *
	 * @param name name of a project to be created
	 * @return ProjectRequest instatnce
	 */
	public ProjectRequest recreateProject(String name) {
		return recreateProject(new ProjectRequestBuilder().withNewMetadata().withName(name).endMetadata().build());
	}

	/**
	 * Creates or recreates project specified by projectRequest instance.
	 *
	 * @return ProjectRequest instatnce
	 */
	public ProjectRequest recreateProject(ProjectRequest projectRequest) {
		boolean deleted = deleteProject(projectRequest.getMetadata().getName());
		if (deleted) {
			BooleanSupplier bs = () -> getProject(projectRequest.getMetadata().getName()) == null;
			new SimpleWaiter(bs, TimeUnit.MILLISECONDS, WaitingConfig.timeout(), "Waiting for old project deletion before creating new one").waitFor();
		}
		return createProjectRequest(projectRequest);
	}

	/**
	 * Tries to retreive project with name 'name'. Swallows KubernetesClientException
	 * if project doesn't exist or isn't accessible for user.
	 *
	 * @param name name of requested project.
	 * @return Project instance if accessible otherwise null.
	 */
	public Project getProject(String name) {
		try {
			return projects().withName(name).get();
		} catch (KubernetesClientException e) {
			return null;
		}
	}

	public Project getProject() {
		try {
			return projects().withName(getNamespace()).get();
		} catch (KubernetesClientException e) {
			return null;
		}
	}

	public boolean deleteProject() {
		return deleteProject(getNamespace());
	}

	public boolean deleteProject(String name) {
		return getProject(name) != null ? projects().withName(name).delete() : false;
	}

	// ImageStreams
	public ImageStream createImageStream(ImageStream imageStream) {
		return imageStreams().create(imageStream);
	}

	public ImageStream getImageStream(String name) {
		return imageStreams().withName(name).get();
	}

	public List<ImageStream> getImageStreams() {
		return imageStreams().list().getItems();
	}

	public boolean deleteImageStream(ImageStream imageStream) {
		return imageStreams().delete(imageStream);
	}

	// Pods
	public Pod createPod(Pod pod) {
		return pods().create(pod);
	}

	public Pod getPod(String name) {
		return pods().withName(name).get();
	}

	public String getPodLog(String deploymentConfigName) {
		return getPodLog(this.getAnyPod(deploymentConfigName));
	}

	public String getPodLog(Pod pod) {
		return pods().withName(pod.getMetadata().getName()).getLog();
	}

	public Reader getPodLogReader(Pod pod) {
		return pods().withName(pod.getMetadata().getName()).getLogReader();
	}

	public Observable<String> observePodLog(String dcName) {
		return observePodLog(getAnyPod(dcName));
	}

	public Observable<String> observePodLog(Pod pod) {
		LogWatch watcher = pods().withName(pod.getMetadata().getName()).watchLog();
		return StringObservable.byLine(StringObservable.from(new InputStreamReader(watcher.getOutput())));
	}

	public List<Pod> getPods() {
		return pods().list().getItems();
	}

	/**
	 * @param deploymentConfigName name of deploymentConfig
	 * @return all active pods created by specified deploymentConfig
	 */
	public List<Pod> getPods(String deploymentConfigName) {
		return getLabeledPods("deploymentconfig", deploymentConfigName);
	}

	/**
	 * @param deploymentConfigName name of deploymentConfig
	 * @param version deployment version to be retrieved
	 * @return active pods created by deploymentConfig with specified version
	 */
	public List<Pod> getPods(String deploymentConfigName, int version) {
		return getLabeledPods("deployment", deploymentConfigName + "-" + version);
	}

	public List<Pod> getLabeledPods(String key, String value) {
		return getLabeledPods(Collections.singletonMap(key, value));
	}

	public List<Pod> getLabeledPods(Map<String, String> labels) {
		return pods().withLabels(labels).list().getItems();
	}

	public Pod getAnyPod(String deploymentConfigName) {
		return getAnyPod("deploymentconfig", deploymentConfigName);
	}

	public Pod getAnyPod(String key, String value) {
		return getAnyPod(Collections.singletonMap(key, value));
	}

	public Pod getAnyPod(Map<String, String> labels) {
		List<Pod> pods = getLabeledPods(labels);
		return pods.get(new Random().nextInt(pods.size()));
	}

	public boolean deletePod(Pod pod) {
		return deletePod(pod, 0L);
	}

	public boolean deletePod(Pod pod, long gracePeriod) {
		return pods().withName(pod.getMetadata().getName()).withGracePeriod(gracePeriod).delete();
	}

	/**
	 * Deletes pods with specified label.
	 *
	 * @param key key of the label
	 * @param value value of the label
	 * @return True if any pod has been deleted
	 */
	public boolean deletePods(String key, String value) {
		return pods().withLabel(key, value).delete();
	}

	public boolean deletePods(Map<String, String> labels) {
		return pods().withLabels(labels).delete();
	}

	// Secrets
	public Secret createSecret(Secret secret) {
		return secrets().create(secret);
	}

	public Secret getSecret(String name) {
		return secrets().withName(name).get();
	}

	public List<Secret> getSecrets() {
		return secrets().list().getItems();
	}

	/**
	 * Retrieves secrets that aren't considered default. Secrets that are left out contain type starting with 'kubernetes.io/'.
	 *
	 * @return List of secrets that aren't considered default.
	 */
	public List<Secret> getUserSecrets() {
		return secrets().withoutLabel(OpenShift.KEEP_LABEL).list().getItems().stream()
				.filter(s -> !s.getType().startsWith("kubernetes.io/"))
				.collect(Collectors.toList());
	}

	public boolean deleteSecret(Secret secret) {
		return secrets().delete(secret);
	}

	// Services
	public Service createService(Service service) {
		return services().create(service);
	}

	public Service getService(String name) {
		return services().withName(name).get();
	}

	public List<Service> getServices() {
		return services().list().getItems();
	}

	public boolean deleteService(Service service) {
		return services().delete(service);
	}

	// Endpoints
	public Endpoints createEndpoint(Endpoints endpoint) {
		return endpoints().create(endpoint);
	}

	public Endpoints getEndpoint(String name) {
		return endpoints().withName(name).get();
	}

	public List<Endpoints> getEndpoints() {
		return endpoints().list().getItems();
	}

	public boolean deleteEndpoint(Endpoints endpoint) {
		return endpoints().delete(endpoint);
	}

	// Routes
	public Route createRoute(Route route) {
		return routes().create(route);
	}

	public Route getRoute(String name) {
		return routes().withName(name).get();
	}

	public List<Route> getRoutes() {
		return routes().list().getItems();
	}

	public boolean deleteRoute(Route route) {
		return routes().delete(route);
	}

	/**
	 * Generates hostname as is expected to be generated by OpenShift instance.
	 *
	 * @param routeName prefix for route hostname
	 * @return Hostname as is expected to be generated by OpenShift
	 */
	public String generateHostname(String routeName) {
		if (routeSuffix == null) {
			if (StringUtils.isNotBlank(cz.xtf.core.config.OpenShiftConfig.routeDomain())) {
				routeSuffix = cz.xtf.core.config.OpenShiftConfig.routeDomain();
			} else {
				routeSuffix = retrieveRouteSuffix();
			}
		}
		return routeName + "-" + getNamespace() + "." + routeSuffix;
	}

	private String retrieveRouteSuffix() {
		Route route = new Route();
		route.setMetadata(new ObjectMetaBuilder().withName("probing-route").build());
		route.setSpec(new RouteSpecBuilder().withNewTo().withKind("Service").withName("imaginary-service").endTo().build());

		route = createRoute(route);
		deleteRoute(route);

		return route.getSpec().getHost().replaceAll("^[^\\.]*\\.(.*)", "$1");
	}

	// ReplicationControllers - Only for internal usage with clean
	private List<ReplicationController> getReplicationControllers() {
		return replicationControllers().list().getItems();
	}

	private boolean deleteReplicationController(ReplicationController replicationController) {
		return replicationControllers().withName(replicationController.getMetadata().getName()).cascading(false).delete();
	}

	// DeploymentConfigs
	public DeploymentConfig createDeploymentConfig(DeploymentConfig deploymentConfig) {
		return deploymentConfigs().create(deploymentConfig);
	}

	public DeploymentConfig getDeploymentConfig(String name) {
		return deploymentConfigs().withName(name).get();
	}

	public List<DeploymentConfig> getDeploymentConfigs() {
		return deploymentConfigs().list().getItems();
	}

	/**
	 * Returns first container environment variables.
	 *
	 * @param name name of deploymentConfig
	 * @return Map of environment variables
	 */
	public Map<String, String> getDeploymentConfigEnvVars(String name) {
		return getDeploymentConfig(name).getSpec().getTemplate().getSpec().getContainers().get(0).getEnv().stream().collect(Collectors.toMap(EnvVar::getName, EnvVar::getValue));
	}

	public DeploymentConfig updateDeploymentconfig(DeploymentConfig deploymentConfig) {
		return deploymentConfigs().withName(deploymentConfig.getMetadata().getName()).replace(deploymentConfig);
	}

	/**
	 * Updates deployment config environment variables with envVars values.
	 *
	 * @param name name of deploymentConfig
	 * @param envVars environment variables
	 */
	public DeploymentConfig updateDeploymentConfigEnvVars(String name, Map<String, String> envVars) {
		DeploymentConfig dc = getDeploymentConfig(name);

		List<EnvVar> vars = envVars.entrySet().stream().map(x -> new EnvVarBuilder().withName(x.getKey()).withValue(x.getValue()).build()).collect(Collectors.toList());
		dc.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv().removeIf(x -> envVars.containsKey(x.getName()));
		dc.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv().addAll(vars);

		return updateDeploymentconfig(dc);
	}

	public boolean deleteDeploymentConfig(DeploymentConfig deploymentConfig) {
		return deleteDeploymentConfig(deploymentConfig, false);
	}

	public boolean deleteDeploymentConfig(DeploymentConfig deploymentConfig, boolean cascading) {
		return deploymentConfigs().withName(deploymentConfig.getMetadata().getName()).cascading(cascading).delete();
	}

	/**
	 * Scales deployment config to specified number of replicas.
	 *
	 * @param name name of deploymentConfig
	 * @param replicas number of target replicas
	 */
	public void scale(String name, int replicas) {
		deploymentConfigs().withName(name).scale(replicas);
	}

	/**
	 * Redeploys deployment config to latest version.
	 *
	 * @param name name of deploymentConfig
	 */
	public void deployLatest(String name) {
		deploymentConfigs().withName(name).deployLatest();
	}

	// Builds
	public Build getBuild(String name) {
		return inNamespace(getNamespace()).builds().withName(name).get();
	}

	public Build getLatestBuild(String buildConfigName) {
		long lastVersion = buildConfigs().withName(buildConfigName).get().getStatus().getLastVersion();
		return getBuild(buildConfigName + "-" + lastVersion);
	}

	public List<Build> getBuilds() {
		return builds().list().getItems();
	}

	public String getBuildLog(Build build) {
		return builds().withName(build.getMetadata().getName()).getLog();
	}

	public Reader getBuildLogReader(Build build) {
		return builds().withName(build.getMetadata().getName()).getLogReader();
	}

	public boolean deleteBuild(Build build) {
		return builds().delete(build);
	}

	public Build startBuild(String buildConfigName) {
		BuildRequest request = new BuildRequestBuilder().withNewMetadata().withName(buildConfigName).endMetadata().build();
		return buildConfigs().withName(buildConfigName).instantiate(request);
	}

	public Build startBinaryBuild(String buildConfigName, File file) {
		return buildConfigs().withName(buildConfigName).instantiateBinary().fromFile(file);
	}

	// BuildConfigs
	public BuildConfig createBuildConfig(BuildConfig buildConfig) {
		return buildConfigs().create(buildConfig);
	}

	public BuildConfig getBuildConfig(String name) {
		return buildConfigs().withName(name).get();
	}

	public List<BuildConfig> getBuildConfigs() {
		return buildConfigs().list().getItems();
	}

	/**
	 * Returns environment variables of buildConfig specified under sourceStrategy.
	 *
	 * @param name name of buildConfig
	 * @return environment variables
	 */
	public Map<String, String> getBuildConfigEnvVars(String name) {
		return getBuildConfig(name).getSpec().getStrategy().getSourceStrategy().getEnv().stream().collect(Collectors.toMap(EnvVar::getName, EnvVar::getValue));
	}

	public BuildConfig updateBuildConfig(BuildConfig buildConfig) {
		return buildConfigs().withName(buildConfig.getMetadata().getName()).replace(buildConfig);
	}

	/**
	 * Updates build config with specified environment variables.
	 *
	 * @param name name of buildConfig
	 * @param envVars environment variables
	 */
	public BuildConfig updateBuildConfigEnvVars(String name, Map<String, String> envVars) {
		BuildConfig bc = getBuildConfig(name);

		List<EnvVar> vars = envVars.entrySet().stream().map(x -> new EnvVarBuilder().withName(x.getKey()).withValue(x.getValue()).build()).collect(Collectors.toList());
		bc.getSpec().getStrategy().getSourceStrategy().getEnv().removeIf(x -> envVars.containsKey(x.getName()));
		bc.getSpec().getStrategy().getSourceStrategy().getEnv().addAll(vars);

		return updateBuildConfig(bc);
	}

	public boolean deleteBuildConfig(BuildConfig buildConfig) {
		return buildConfigs().delete(buildConfig);
	}

	// ServiceAccounts
	public ServiceAccount createServiceAccount(ServiceAccount serviceAccount) {
		return serviceAccounts().create(serviceAccount);
	}

	public ServiceAccount getServiceAccount(String name) {
		return serviceAccounts().withName(name).get();
	}

	public List<ServiceAccount> getServiceAccounts() {
		return serviceAccounts().list().getItems();
	}

	/**
	 * Retrieves service accounts that aren't considered default.
	 * Service accounts that are left out from list:
	 * <ul>
	 * <li>builder</li>
	 * <li>default</li>
	 * <li>deployer</li>
	 * </ul>
	 *
	 * @return List of service accounts that aren't considered default.
	 */
	public List<ServiceAccount> getUserServiceAccounts() {
		return serviceAccounts().withoutLabel(KEEP_LABEL).list().getItems().stream()
				.filter(sa -> !sa.getMetadata().getName().matches("builder|default|deployer"))
				.collect(Collectors.toList());
	}

	public boolean deleteServiceAccount(ServiceAccount serviceAccount) {
		return serviceAccounts().delete(serviceAccount);
	}

	// RoleBindings
	public OpenshiftRoleBinding createRoleBinding(OpenshiftRoleBinding roleBinding) {
		return roleBindings().create(roleBinding);
	}

	public OpenshiftRoleBinding getRoleBinding(String name) {
		return roleBindings().withName(name).get();
	}

	public List<OpenshiftRoleBinding> getRoleBindings() {
		return roleBindings().list().getItems();
	}

	public List<OpenshiftRole> getRoles() {
		return roles().list().getItems();
	}

	/**
	 * Retrieves role bindings that aren't considered default.
	 * Role bindings that are left out from list:
	 * <ul>
	 * <li>admin</li>
	 * <li>system:deployers</li>
	 * <li>system:image-builders</li>
	 * <li>system:image-pullers</li>
	 * </ul>
	 *
	 * @return List of role bindings that aren't considered default.
	 */
	public List<OpenshiftRoleBinding> getUserRoleBindings() {
		return roleBindings().withoutLabel(KEEP_LABEL).list().getItems().stream()
				.filter(rb -> !rb.getMetadata().getName().matches("admin|system:deployers|system:image-builders|system:image-pullers"))
				.collect(Collectors.toList());
	}

	public boolean deleteRoleBinding(OpenshiftRoleBinding roleBinding) {
		return roleBindings().delete(roleBinding);
	}

	public OpenshiftRoleBinding addRoleToUser(String roleName, String username) {
		OpenshiftRoleBinding roleBinding = getOrCreateRoleBinding(roleName);

		addSubjectToRoleBinding(roleBinding, "User", username);
		addUserNameToRoleBinding(roleBinding, username);

		return updateRoleBinding(roleBinding);
	}

	public OpenshiftRoleBinding addRoleToServiceAccount(String roleName, String serviceAccountName) {
		OpenshiftRoleBinding roleBinding = getOrCreateRoleBinding(roleName);

		addSubjectToRoleBinding(roleBinding, "ServiceAccount", serviceAccountName);
		addUserNameToRoleBinding(roleBinding, String.format("system:serviceaccount:%s:%s", getNamespace(), serviceAccountName));

		return updateRoleBinding(roleBinding);
	}

	public OpenshiftRoleBinding addRoleToGroup(String roleName, String groupName) {
		OpenshiftRoleBinding roleBinding = getOrCreateRoleBinding(roleName);

		addSubjectToRoleBinding(roleBinding, "SystemGroup", groupName);
		addGroupNameToRoleBinding(roleBinding, groupName);

		return updateRoleBinding(roleBinding);
	}

	private OpenshiftRoleBinding getOrCreateRoleBinding(String name) {
		OpenshiftRoleBinding roleBinding = getRoleBinding(name);

		if (roleBinding == null) {
			roleBinding = new OpenshiftRoleBindingBuilder()
					.withNewMetadata().withName(name).endMetadata()
					.withNewRoleRef().withName(name).endRoleRef()
					.build();
			createRoleBinding(roleBinding);
		}
		return roleBinding;
	}

	public OpenshiftRoleBinding updateRoleBinding(OpenshiftRoleBinding roleBinding) {
		return roleBindings().withName(roleBinding.getMetadata().getName()).replace(roleBinding);
	}

	private void addSubjectToRoleBinding(OpenshiftRoleBinding roleBinding, String entityKind, String entityName) {
		ObjectReference subject = new ObjectReferenceBuilder().withKind(entityKind).withName(entityName).build();

		if (roleBinding.getSubjects().stream().noneMatch(x -> x.getName().equals(subject.getName()) && x.getKind().equals(subject.getKind()))) {
			roleBinding.getSubjects().add(subject);
		}
	}

	private void addUserNameToRoleBinding(OpenshiftRoleBinding roleBinding, String userName) {
		if (roleBinding.getUserNames() == null) {
			roleBinding.setUserNames(new ArrayList<>());
		}
		if (!roleBinding.getUserNames().contains(userName)) {
			roleBinding.getUserNames().add(userName);
		}
	}

	private void addGroupNameToRoleBinding(OpenshiftRoleBinding roleBinding, String groupName) {
		if (roleBinding.getGroupNames() == null) {
			roleBinding.setGroupNames(new ArrayList<>());
		}
		if (!roleBinding.getGroupNames().contains(groupName)) {
			roleBinding.getGroupNames().add(groupName);
		}
	}

	public OpenshiftRoleBinding removeRoleFromServiceAccount(String roleName, String serviceAccountName) {
		return removeRoleFromEntity(roleName, "ServiceAccount", serviceAccountName, String.format("system:serviceaccount:%s:%s", getNamespace(), serviceAccountName));
	}

	public OpenshiftRoleBinding removeRoleFromEntity(String roleName, String entityKind, String entityName, String userName) {
		OpenshiftRoleBinding roleBinding = this.roleBindings().withName(roleName).get();

		if (roleBinding != null) {
			roleBinding.getSubjects().remove(new ObjectReferenceBuilder().withKind(entityKind).withName(entityName).withNamespace(getNamespace()).build());
			roleBinding.getUserNames().remove(userName);

			return updateRoleBinding(roleBinding);
		}
		return null;
	}

	// ResourceQuotas
	public ResourceQuota createResourceQuota(ResourceQuota resourceQuota) {
		return resourceQuotas().create(resourceQuota);
	}

	public ResourceQuota getResourceQuota(String name) {
		return resourceQuotas().withName(name).get();
	}

	public boolean deleteResourceQuota(ResourceQuota resourceQuota) {
		return resourceQuotas().delete(resourceQuota);
	}

	// Persistent volume claims
	public PersistentVolumeClaim createPersistentVolumeClaim(PersistentVolumeClaim pvc) {
		return persistentVolumeClaims().create(pvc);
	}

	public PersistentVolumeClaim getPersistentVolumeClaim(String name) {
		return persistentVolumeClaims().withName(name).get();
	}

	public List<PersistentVolumeClaim> getPersistentVolumeClaims() {
		return persistentVolumeClaims().list().getItems();
	}

	public boolean deletePersistentVolumeClaim(PersistentVolumeClaim pvc) {
		return persistentVolumeClaims().delete(pvc);
	}

	// HorizontalPodAutoscalers
	public HorizontalPodAutoscaler createHorizontalPodAutoscaler(HorizontalPodAutoscaler hpa) {
		return autoscaling().horizontalPodAutoscalers().create(hpa);
	}

	public HorizontalPodAutoscaler getHorizontalPodAutoscaler(String name) {
		return autoscaling().horizontalPodAutoscalers().withName(name).get();
	}

	public List<HorizontalPodAutoscaler> getHorizontalPodAutoscalers() {
		return autoscaling().horizontalPodAutoscalers().list().getItems();
	}

	public boolean deleteHorizontalPodAutoscaler(HorizontalPodAutoscaler hpa) {
		return autoscaling().horizontalPodAutoscalers().delete(hpa);
	}

	// ConfigMaps
	public ConfigMap createConfigMap(ConfigMap configMap) {
		return configMaps().create(configMap);
	}

	public ConfigMap getConfigMap(String name) {
		return configMaps().withName(name).get();
	}

	public List<ConfigMap> getConfigMaps() {
		return configMaps().list().getItems();
	}

	public boolean deleteConfigMap(ConfigMap configMap) {
		return configMaps().delete(configMap);
	}

	// Templates
	public Template createTemplate(Template template) {
		return templates().create(template);
	}

	public Template getTemplate(String name) {
		return templates().withName(name).get();
	}

	public List<Template> getTemplates() {
		return templates().list().getItems();
	}

	public boolean deleteTemplate(String name) {
		return templates().withName(name).delete();
	}

	public boolean deleteTemplate(Template template) {
		return templates().delete(template);
	}

	public Template loadAndCreateTemplate(InputStream is) {
		Template t = templates().load(is).get();
		deleteTemplate(t);

		return createTemplate(t);
	}

	public KubernetesList recreateAndProcessTemplate(Template template, Map<String, String> parameters) {
		deleteTemplate(template.getMetadata().getName());
		createTemplate(template);

		return processTemplate(template.getMetadata().getName(), parameters);
	}

	public KubernetesList recreateAndProcessAndDeployTemplate(Template template, Map<String, String> parameters) {
		return createResources(recreateAndProcessTemplate(template, parameters));
	}

	public KubernetesList processTemplate(String name, Map<String, String> parameters) {
		ParameterValue[] values = processParameters(parameters);
		return templates().withName(name).process(values);
	}

	public KubernetesList processAndDeployTemplate(String name, Map<String, String> parameters) {
		return createResources(processTemplate(name, parameters));
	}

	private ParameterValue[] processParameters(Map<String, String> parameters) {
		return parameters.entrySet().stream().map(entry -> new ParameterValue(entry.getKey(), entry.getValue())).collect(Collectors.toList()).toArray(new ParameterValue[parameters.size()]);
	}

	// Nodes
	public Node getNode(String name) {
		return nodes().withName(name).get();
	}

	public List<Node> getNodes() {
		return nodes().list().getItems();
	}

	public List<Node> getNodes(Map<String, String> labels) {
		return nodes().withLabels(labels).list().getItems();
	}

	// Events
	public List<Event> getEvents() {
		return events().list().getItems();
	}

	// Port Forward
	public LocalPortForward portForward(String deploymentConfigName, int remotePort, int localPort) {
		return portForward(getAnyPod(deploymentConfigName), remotePort, localPort);
	}

	public LocalPortForward portForward(Pod pod, int remotePort, int localPort) {
		return pods().withName(pod.getMetadata().getName()).portForward(remotePort, localPort);
	}

	// PodShell
	public PodShell podShell(String dcName) {
		return podShell(getAnyPod(dcName));
	}

	public PodShell podShell(Pod pod) {
		return new PodShell(this, pod);
	}

	// Clean up function
	/**
	 * Deletes all* resources in namespace. Doesn't wait till all are deleted. <br/>
	 * <br/>
	 * <p>
	 * * Only user created secrets, service accounts and role bindings are deleted. Default will remain.
	 *
	 * @see #getUserSecrets()
	 * @see #getUserServiceAccounts()
	 * @see #getUserRoleBindings()
	 */
	public Waiter clean() {
		// keep the order for deletion to prevent K8s creating resources again
		templates().withoutLabel(KEEP_LABEL).delete();
		apps().deployments().withoutLabel(KEEP_LABEL).delete();
		apps().statefulSets().withoutLabel(KEEP_LABEL).delete();
		batch().jobs().withoutLabel(KEEP_LABEL).delete();
		deploymentConfigs().withoutLabel(KEEP_LABEL).delete();
		replicationControllers().withoutLabel(KEEP_LABEL).delete();
		buildConfigs().withoutLabel(KEEP_LABEL).delete();
		imageStreams().withoutLabel(KEEP_LABEL).delete();
		endpoints().withoutLabel(KEEP_LABEL).delete();
		services().withoutLabel(KEEP_LABEL).delete();
		builds().withoutLabel(KEEP_LABEL).delete();
		routes().withoutLabel(KEEP_LABEL).delete();
		pods().withoutLabel(KEEP_LABEL).withGracePeriod(0).delete();
		persistentVolumeClaims().withoutLabel(KEEP_LABEL).delete();
		autoscaling().horizontalPodAutoscalers().withoutLabel(KEEP_LABEL).delete();
		configMaps().withoutLabel(KEEP_LABEL).delete();
		getUserSecrets().forEach(this::deleteSecret);
		getUserServiceAccounts().forEach(this::deleteServiceAccount);
		getUserRoleBindings().forEach(this::deleteRoleBinding);
		roles().withoutLabel(KEEP_LABEL).delete();

		return waiters.isProjectClean();
	}

	// Logs storing
	public Path storePodLog(Pod pod, Path dirPath, String fileName) throws IOException {
		String log = getPodLog(pod);
		return storeLog(log, dirPath, fileName);
	}

	public Path storeBuildLog(Build build, Path dirPath, String fileName) throws IOException {
		String log = getBuildLog(build);
		return storeLog(log, dirPath, fileName);
	}

	private Path storeLog(String log, Path dirPath, String fileName) throws IOException {
		Path filePath = dirPath.resolve(fileName);

		Files.createDirectories(dirPath);
		Files.createFile(filePath);
		Files.write(filePath, log.getBytes());

		return filePath;
	}

	// Waiting
	public OpenShiftWaiters waiters() {
		return waiters;
	}
}
