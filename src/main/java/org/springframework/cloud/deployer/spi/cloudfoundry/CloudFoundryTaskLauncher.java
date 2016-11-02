/*
 * Copyright 2016 the original author or authors.
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

package org.springframework.cloud.deployer.spi.cloudfoundry;

import static org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryDeploymentProperties.BUILDPACK_PROPERTY_KEY;
import static org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryDeploymentProperties.DISK_PROPERTY_KEY;
import static org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryDeploymentProperties.MEMORY_PROPERTY_KEY;
import static org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryDeploymentProperties.SERVICES_PROPERTY_KEY;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cloudfoundry.client.CloudFoundryClient;
import org.cloudfoundry.client.v2.CloudFoundryException;
import org.cloudfoundry.client.v2.applications.SummaryApplicationResponse;
import org.cloudfoundry.client.v2.applications.UpdateApplicationRequest;
import org.cloudfoundry.client.v2.applications.UpdateApplicationResponse;
import org.cloudfoundry.client.v3.tasks.CancelTaskRequest;
import org.cloudfoundry.client.v3.tasks.CancelTaskResponse;
import org.cloudfoundry.client.v3.tasks.CreateTaskRequest;
import org.cloudfoundry.client.v3.tasks.CreateTaskResponse;
import org.cloudfoundry.client.v3.tasks.GetTaskRequest;
import org.cloudfoundry.client.v3.tasks.GetTaskResponse;
import org.cloudfoundry.operations.CloudFoundryOperations;
import org.cloudfoundry.operations.applications.AbstractApplicationSummary;
import org.cloudfoundry.operations.applications.ApplicationDetail;
import org.cloudfoundry.operations.applications.ApplicationHealthCheck;
import org.cloudfoundry.operations.applications.ApplicationSummary;
import org.cloudfoundry.operations.applications.GetApplicationRequest;
import org.cloudfoundry.operations.applications.PushApplicationRequest;
import org.cloudfoundry.operations.applications.StartApplicationRequest;
import org.cloudfoundry.operations.applications.StopApplicationRequest;
import org.cloudfoundry.operations.services.BindServiceInstanceRequest;
import org.cloudfoundry.operations.services.ServiceInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.cloud.deployer.spi.task.LaunchState;
import org.springframework.cloud.deployer.spi.task.TaskLauncher;
import org.springframework.cloud.deployer.spi.task.TaskStatus;
import org.springframework.util.StringUtils;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * {@link TaskLauncher} implementation for CloudFoundry.  When a task is launched, if it has not previously been
 * deployed, the app is created, the package is uploaded, and the droplet is created before launching the actual
 * task.  If the app has been deployed previously, the app/package/droplet is reused and a new task is created.
 *
 * @author Greg Turnquist
 * @author Michael Minella
 * @author Ben Hale
 */
public class CloudFoundryTaskLauncher implements TaskLauncher {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	private static final Logger logger = LoggerFactory.getLogger(CloudFoundryTaskLauncher.class);

	private final CloudFoundryClient client;

	private final CloudFoundryDeploymentProperties deploymentProperties;

	private final CloudFoundryOperations operations;

	private final String space;

	public CloudFoundryTaskLauncher(CloudFoundryClient client,
									CloudFoundryDeploymentProperties deploymentProperties,
									CloudFoundryOperations operations,
									String space) {
		this.client = client;
		this.deploymentProperties = deploymentProperties;
		this.operations = operations;
		this.space = space;
	}

	/**
	 * Setup a reactor flow to cancel a running task.  This implementation opts to be asynchronous.
	 *
	 * @param id the task's id to be canceled as returned from the {@link TaskLauncher#launch(AppDeploymentRequest)}
	 */
	@Override
	public void cancel(String id) {
		requestCancelTask(id)
			.timeout(Duration.ofSeconds(this.deploymentProperties.getTaskTimeout()))
			.doOnSuccess(r -> logger.info("Task {} cancellation successful", id))
			.doOnError(t -> logger.error(String.format("Task %s cancellation failed", id), t))
			.subscribe();
	}

	/**
	 * Set up a reactor flow to launch a task. Before launch, check if the base application exists. If not, deploy then launch task.
	 *
	 * @param request description of the application to be launched
	 * @return name of the launched task, returned without waiting for reactor pipeline to complete
	 */
	@Override
	public String launch(AppDeploymentRequest request) {
		return getOrDeployApplication(request)
			.then(application -> launchTask(application, request))
			.doOnSuccess(r -> logger.info("Task {} launch successful", request))
			.doOnError(t -> logger.error(String.format("Task %s launch failed", request), t))
			.block(Duration.ofSeconds(this.deploymentProperties.getTaskTimeout()));
	}

	/**
	 * Lookup the current status based on task id.
	 *
	 * @param id taskId as returned from the {@link TaskLauncher#launch(AppDeploymentRequest)}
	 * @return the current task status
	 */
	@Override
	public TaskStatus status(String id) {
		BiFunction<Throwable, String, Mono<TaskStatus>> recoverFrom404 = this::toTaskStatus;
		return requestGetTask(id)
			.map(this::toTaskStatus)
			.otherwise(e -> recoverFrom404.apply(e, id))
			.doOnSuccess(r -> logger.info("Task {} status successful", id))
			.doOnError(t -> logger.error(String.format("Task %s status failed", id), t))
			.block(Duration.ofSeconds(this.deploymentProperties.getTaskTimeout()));
	}

	private Mono<Void> bindServices(String name, AppDeploymentRequest request) {
		Set<String> servicesToBind = servicesToBind(request);

		return requestListServiceInstances()
			.filter(serviceInstance -> servicesToBind.contains(serviceInstance.getName()))
			.flatMap(serviceInstance -> requestBindService(name, serviceInstance.getName()))
			.then();
	}

	private String buildpack(AppDeploymentRequest request) {
		return Optional.ofNullable(request.getDeploymentProperties().get(BUILDPACK_PROPERTY_KEY))
			.orElse(this.deploymentProperties.getBuildpack());
	}

	private Mono<AbstractApplicationSummary> deployApplication(AppDeploymentRequest request) {
		String name = request.getDefinition().getName();

		return pushApplication(name, request)
			.then(requestGetApplication(name))
			.then(application -> setEnvironmentVariables(application.getId(), getEnvironmentVariables(request.getDefinition().getProperties()))
				.then(bindServices(name, request))
				.then(startApplication(name))
				.then(stopApplication(name))
				.then(Mono.just(application)));
	}

	private int diskQuota(AppDeploymentRequest request) {
		return Optional.ofNullable(request.getDeploymentProperties().get(DISK_PROPERTY_KEY))
			.map(Integer::parseInt)
			.orElse(this.deploymentProperties.getDisk());
	}

	private Path getApplication(AppDeploymentRequest request) {
		try {
			return request.getResource().getFile().toPath();
		} catch (IOException e) {
			throw Exceptions.propagate(e);
		}
	}

	private String getCommand(SummaryApplicationResponse application, AppDeploymentRequest request) {
		return Stream.concat(Stream.of(application.getDetectedStartCommand()), request.getCommandlineArguments().stream())
			.collect(Collectors.joining(" "));
	}

	private Map<String, String> getEnvironmentVariables(Map<String, String> properties) {
		try {
			return Collections.singletonMap("SPRING_APPLICATION_JSON", OBJECT_MAPPER.writeValueAsString(properties));
		} catch (JsonProcessingException e) {
			throw Exceptions.propagate(e);
		}
	}

	private Mono<AbstractApplicationSummary> getOptionalApplication(AppDeploymentRequest request) {
		String name = request.getDefinition().getName();

		return requestListApplications()
			.filter(application -> name.equals(application.getName()))
			.singleOrEmpty()
			.cast(AbstractApplicationSummary.class);
	}

	private Mono<SummaryApplicationResponse> getOrDeployApplication(AppDeploymentRequest request) {
		return getOptionalApplication(request)
			.otherwiseIfEmpty(deployApplication(request))
			.then(application -> requestGetApplicationSummary(application.getId()));
	}

	private Mono<String> launchTask(SummaryApplicationResponse application, AppDeploymentRequest request) {
		return requestCreateTask(application.getId(), getCommand(application, request), memory(request), request.getDefinition().getName())
			.map(CreateTaskResponse::getId);
	}

	private int memory(AppDeploymentRequest request) {
		return Optional.ofNullable(request.getDeploymentProperties().get(MEMORY_PROPERTY_KEY))
			.map(Integer::parseInt)
			.orElse(this.deploymentProperties.getMemory());
	}

	private Mono<Void> pushApplication(String name, AppDeploymentRequest request) {
		return requestPushApplication(PushApplicationRequest.builder()
			.application(getApplication(request))
			.buildpack(buildpack(request))
			.command("/bin/nc -l $PORT")
			.diskQuota(diskQuota(request))
			.healthCheckType(ApplicationHealthCheck.NONE)
			.memory(memory(request))
			.name(name)
			.noRoute(true)
			.noStart(true)
			.build());
	}

	private Mono<Void> requestBindService(String applicationName, String serviceInstanceName) {
		return this.operations.services()
			.bind(BindServiceInstanceRequest.builder()
				.applicationName(applicationName)
				.serviceInstanceName(serviceInstanceName)
				.build());
	}

	private Mono<CancelTaskResponse> requestCancelTask(String taskId) {
		return this.client.tasks()
			.cancel(CancelTaskRequest.builder()
				.taskId(taskId)
				.build());
	}

	private Mono<CreateTaskResponse> requestCreateTask(String applicationId, String command, int memory, String name) {
		return this.client.tasks()
			.create(CreateTaskRequest.builder()
				.applicationId(applicationId)
				.command(command)
				.memoryInMb(memory)
				.name(name)
				.build());
	}

	private Mono<ApplicationDetail> requestGetApplication(String name) {
		return this.operations.applications()
			.get(GetApplicationRequest.builder()
				.name(name)
				.build());
	}

	private Mono<SummaryApplicationResponse> requestGetApplicationSummary(String applicationId) {
		return this.client.applicationsV2()
			.summary(org.cloudfoundry.client.v2.applications.SummaryApplicationRequest.builder()
				.applicationId(applicationId)
				.build());
	}

	private Mono<GetTaskResponse> requestGetTask(String taskId) {
		return this.client.tasks()
			.get(GetTaskRequest.builder()
				.taskId(taskId)
				.build());
	}

	private Flux<ApplicationSummary> requestListApplications() {
		return this.operations.applications()
			.list();
	}

	private Flux<ServiceInstance> requestListServiceInstances() {
		return this.operations.services()
			.listInstances();
	}

	private Mono<Void> requestPushApplication(PushApplicationRequest request) {
		return this.operations.applications()
			.push(request);
	}

	private Mono<Void> requestStartApplication(String name, Duration stagingTimeout, Duration startupTimeout) {
		return this.operations.applications()
			.start(StartApplicationRequest.builder()
				.name(name)
				.stagingTimeout(stagingTimeout)
				.startupTimeout(startupTimeout)
				.build());
	}

	private Mono<Void> requestStopApplication(String name) {
		return this.operations.applications()
			.stop(StopApplicationRequest.builder()
				.name(name)
				.build());
	}

	private Mono<UpdateApplicationResponse> requestUpdateApplication(String applicationId, Map<String, String> environmentVariables) {
		return this.client.applicationsV2()
			.update(UpdateApplicationRequest.builder()
				.applicationId(applicationId)
				.environmentJsons(environmentVariables)
				.build());
	}

	private Set<String> servicesToBind(AppDeploymentRequest request) {
		Set<String> services = new HashSet<>();
		services.addAll(this.deploymentProperties.getServices());
		services.addAll(StringUtils.commaDelimitedListToSet(request.getDeploymentProperties().get(SERVICES_PROPERTY_KEY)));
		return services;
	}

	private Mono<UpdateApplicationResponse> setEnvironmentVariables(String applicationId, Map<String, String> environmentVariables) {
		return requestUpdateApplication(applicationId, environmentVariables);
	}

	private Mono<Void> startApplication(String name) {
		return requestStartApplication(name, this.deploymentProperties.getStagingTimeout(), this.deploymentProperties.getStartupTimeout());
	}

	private Mono<Void> stopApplication(String name) {
		return requestStopApplication(name);
	}

	private Mono<TaskStatus> toTaskStatus(Throwable throwable, String id) {
		if ((throwable instanceof CloudFoundryException)
			&& ((CloudFoundryException) throwable).getCode() == 10010) {
			return Mono.just(new TaskStatus(id, LaunchState.unknown, null));
		} else {
			return Mono.error(throwable);
		}
	}

	private TaskStatus toTaskStatus(GetTaskResponse response) {
		switch (response.getState()) {
			case SUCCEEDED_STATE:
				return new TaskStatus(response.getId(), LaunchState.complete, null);
			case RUNNING_STATE:
				return new TaskStatus(response.getId(), LaunchState.running, null);
			case PENDING_STATE:
				return new TaskStatus(response.getId(), LaunchState.launching, null);
			case CANCELING_STATE:
				return new TaskStatus(response.getId(), LaunchState.cancelled, null);
			case FAILED_STATE:
				return new TaskStatus(response.getId(), LaunchState.failed, null);
			default:
				throw new IllegalStateException(String.format("Unsupported CF task state %s", response.getState()));
		}
	}

}
