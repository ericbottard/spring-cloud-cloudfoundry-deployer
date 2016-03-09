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

import java.io.IOException;
import java.time.Duration;

import org.cloudfoundry.client.CloudFoundryClient;
import org.cloudfoundry.client.v3.applications.Application;
import org.cloudfoundry.client.v3.applications.CreateApplicationRequest;
import org.cloudfoundry.client.v3.applications.ListApplicationTasksRequest;
import org.cloudfoundry.client.v3.applications.ListApplicationsRequest;
import org.cloudfoundry.client.v3.packages.CreatePackageRequest;
import org.cloudfoundry.client.v3.packages.Package;
import org.cloudfoundry.client.v3.packages.StagePackageRequest;
import org.cloudfoundry.client.v3.packages.StagePackageResponse;
import org.cloudfoundry.client.v3.packages.UploadPackageRequest;
import org.cloudfoundry.client.v3.tasks.CancelTaskRequest;
import org.cloudfoundry.client.v3.tasks.CreateTaskRequest;
import org.cloudfoundry.client.v3.tasks.CreateTaskResponse;
import org.cloudfoundry.client.v3.tasks.Task;
import org.cloudfoundry.client.v3.tasks.TaskResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoProcessor;

import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.cloud.deployer.spi.task.LaunchState;
import org.springframework.cloud.deployer.spi.task.TaskLauncher;
import org.springframework.cloud.deployer.spi.task.TaskStatus;

/**
 * Cloud Foundry based implementation of the Task launcher. This solution handles uploading the bits for
 * a given job if needed.
 *
 * @author Greg Turnquist
 */
public class CloudFoundryTaskLauncher implements TaskLauncher {

	private static final Logger logger = LoggerFactory.getLogger(CloudFoundryTaskLauncher.class);

	private CloudFoundryAppDeployProperties properties;

	private CloudFoundryClient client;

	public CloudFoundryTaskLauncher(CloudFoundryClient client, CloudFoundryAppDeployProperties properties) {

		this.properties = properties;
		this.client = client;
	}

	@Override
	public String launch(AppDeploymentRequest request) {

		if (!taskExists(request)) {
			deploy(request)
					.get(Duration.ofSeconds(120));
		}

		return doLaunchTask(request);
	}

	/**
	 * Does this task exist?
	 *
	 * @param request
	 * @return boolean indicating whether or not the task exists
	 */
	private boolean taskExists(AppDeploymentRequest request) {

		return client.applicationsV3()
				.list(ListApplicationsRequest.builder()
					.name(request.getDefinition().getName())
					.page(1)
					.build())
				.flatMap(response -> Flux.fromIterable(response.getResources()))
				.count()
				.get(Duration.ofSeconds(10)) > 0;
	}

	/**
	 * Create an application with a package, then upload the bits into a staging.
	 *
	 * @param request
	 * @return {@link Mono} with the staged bits.
	 */
	private MonoProcessor<StagePackageResponse> deploy(AppDeploymentRequest request) {

		return createApplication(request.getDefinition().getName())
				.then(this::createPackage)
				.then(packageId -> uploadPackage(packageId, request))
				.then(this::stagePackage)
				.subscribe();
	}

	/**
	 * Using the name of the task, find its applicationId and run it.
	 *
	 * @param request
	 * @return name of the task
	 */
	private String doLaunchTask(AppDeploymentRequest request) {

		return client.applicationsV3()
				.list(ListApplicationsRequest.builder()
						.name(request.getDefinition().getName())
						.page(1)
						.build())
				.flatMap(response -> Flux.fromIterable(response.getResources()))
				.single()
				.map(Application::getId)
				.flatMap(applicationId -> client.tasks()
					.create(CreateTaskRequest.builder()
						.applicationId(applicationId)
						.command("java -jar")
						.build()))
				.single()
				.map(Task::getName)
				.get(Duration.ofSeconds(10));
	}

	/**
	 * Create a new Cloud Foundry application by name
	 *
	 * @param name
	 * @return applicationId
	 */
	private Mono<String> createApplication(String name) {

		return client.applicationsV3()
				.create(CreateApplicationRequest.builder()
					.name(name)
					.build())
				.map(Application::getId);
	}

	/**
	 * Create Cloud Foundry package by applicationId
	 *
	 * @param applicationId
	 * @return packageId
	 */
	private Mono<String> createPackage(String applicationId) {

		return client.packages()
				.create(CreatePackageRequest.builder()
					.applicationId(applicationId)
					.build())
				.map(Package::getId)
				.doOnSubscribe(subscription -> logger.debug("Starting Create Package"));
	}

	/**
	 * Upload bits to a Cloud Foundry application by packageId.
	 *
	 * @param packageId
	 * @param request
	 * @return packageId
	 */
	private Mono<String> uploadPackage(String packageId, AppDeploymentRequest request) {

		try {
			return client.packages()
					.upload(UploadPackageRequest.builder()
						.packageId(packageId)
						.bits(request.getResource().getInputStream())
						.build())
					.map(Package::getId);
		} catch (IOException e) {
			return Mono.error(e);
		}
	}

	/**
	 * Stage a Cloud Foundry application by packageId.
	 *
	 * @param packageId
	 * @return {@link StagePackageResponse}
	 */
	private Mono<StagePackageResponse> stagePackage(String packageId) {

		return client.packages()
				.stage(StagePackageRequest.builder()
					.packageId(packageId)
					.build());
	}

	/**
	 * Run a Cloud Foundry task
	 *
	 * @param applicationId
	 * @return {@link CreateTaskResponse}
	 */
	private Mono<CreateTaskResponse> createTask(String applicationId) {

		return client.tasks()
				.create(CreateTaskRequest.builder()
					.applicationId(applicationId)
					.command("java -jar")
					.build());
	}

	@Override
	public void cancel(String id) {

		client.applicationsV3()
				.list(ListApplicationsRequest.builder()
					.name(id)
					.page(1)
					.build())
				.flatMap(response -> Flux.fromIterable(response.getResources()))
				.single()
				.map(Application::getId)
				.flatMap(applicationId -> client.applicationsV3()
					.listTasks(ListApplicationTasksRequest.builder()
						.applicationId(applicationId)
						.build()))
				.flatMap(response -> Flux.fromIterable(response.getResources()))
				.flatMap(task -> client.tasks()
					.cancel(CancelTaskRequest.builder()
						.taskId(task.getId())
						.build()))
				.single()
				.get(Duration.ofSeconds(10));
	}

	@Override
	public TaskStatus status(String id) {

		return client.applicationsV3()
				.list(ListApplicationsRequest.builder()
					.name(id)
					.page(1)
					.build())
				.flatMap(response -> Flux.fromIterable(response.getResources()))
				.single()
				.map(Application::getId)
				.flatMap(applicationId -> client.applicationsV3()
					.listTasks(ListApplicationTasksRequest.builder()
						.applicationId(applicationId)
						.build()))
				.flatMap(response -> Flux.fromIterable(response.getResources()))
				.map(CloudFoundryTaskLauncher::mapState)
				.map(launchState -> new TaskStatus(id, launchState, null))
				// TODO: You have a problem here.  An application can have multiple tasks
				// TODO: and you need there to only be a single one.  You'll need to reconcile this.
				.single()
				.get(Duration.ofSeconds(10));
	}

	/**
	 * Convert a {@link TaskResource} into a {@Link LaunchState}.
	 *
	 * @param task
	 * @return {@link LaunchState} of the task.
	 */
	private static LaunchState mapState(TaskResource task) {
		switch (task.getState()) {
			case Task.SUCCEEDED_STATE:
				return LaunchState.complete;
			case Task.RUNNING_STATE:
				return LaunchState.running;
			case Task.FAILED_STATE:
				return LaunchState.failed;
			case Task.CANCELING_STATE:
				return LaunchState.cancelled;
			default:
				return LaunchState.unknown;
		}
	}
}
