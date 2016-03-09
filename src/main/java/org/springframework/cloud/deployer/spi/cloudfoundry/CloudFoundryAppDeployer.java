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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.cloudfoundry.client.CloudFoundryClient;
import org.cloudfoundry.client.v3.applications.Application;
import org.cloudfoundry.client.v3.applications.CreateApplicationRequest;
import org.cloudfoundry.client.v3.applications.DeleteApplicationRequest;
import org.cloudfoundry.client.v3.applications.GetApplicationStatisticsRequest;
import org.cloudfoundry.client.v3.applications.ListApplicationsRequest;
import org.cloudfoundry.client.v3.applications.StartApplicationRequest;
import org.cloudfoundry.client.v3.packages.CreatePackageRequest;
import org.cloudfoundry.client.v3.packages.StagePackageRequest;
import org.cloudfoundry.client.v3.packages.UploadPackageRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.tuple.Tuple2;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.deployer.spi.app.AppDeployer;
import org.springframework.cloud.deployer.spi.app.AppStatus;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.web.client.HttpStatusCodeException;

/**
 * A deployer that targets Cloud Foundry using the public API.
 *
 * @author Greg Turnquist
 */
public class CloudFoundryAppDeployer implements AppDeployer {

	private static final Logger logger = LoggerFactory.getLogger(CloudFoundryAppDeployer.class);

	private CloudFoundryAppDeployProperties properties = new CloudFoundryAppDeployProperties();

	private final CloudFoundryClient client;

	private List<Logger> loggers = new ArrayList<>();

	@Autowired
	public CloudFoundryAppDeployer(CloudFoundryAppDeployProperties properties,
	                               CloudFoundryClient client) {
		this.properties = properties;
		this.client = client;
		this.registerCustomerLogger(logger);
	}

	public void registerCustomerLogger(Logger logger) {
		this.loggers.add(logger);
	}

	/**
	 * Carry out the eqivalent of a "cf push".
	 *
	 * @param request the app deployment request
	 * @return
	 * @throws {@link IllegalStateException} is the app is already deployed
	 */
	@Override
	public String deploy(AppDeploymentRequest request) {

		if (!appExists(request.getDefinition().getName())) {

			return createApplication(request)
					.then(applicationId -> createPackage(applicationId))
					.then(response -> uploadPackage(response.getT1(), response.getT2(), request))
					.then(uploadResponse -> stagePackage(uploadResponse.getT1(), uploadResponse.getT2()))
					.then(applicationId -> launchApp(applicationId))
					.subscribe()
					.get(Duration.ofSeconds(120));

		} else {
			throw new IllegalStateException(request.getDefinition().getName() + " is already deployed.");
		}
	}

	/**
	 * Create/Update a Cloud Foundry application using various settings.
	 *
	 * @param request
	 */
	private Mono<String> createApplication(AppDeploymentRequest request) {

		return client.applicationsV3()
			.create(CreateApplicationRequest.builder()
				.name(request.getDefinition().getName())
				.environmentVariables(envVariables(request))
				.build())
			.map(Application::getId);
	}

	private Mono<Tuple2<String,String>> createPackage(String applicationId) {

		return client.packages()
				.create(CreatePackageRequest.builder()
						.applicationId(applicationId)
						.build())
				.map(response -> Tuple2.of(response.getId(), applicationId))
				.doOnSubscribe(subscription -> logger.debug("Starting Create Package"));
	}

	private Mono<Tuple2<String,String>> uploadPackage(String packageId, String applicationId, AppDeploymentRequest request) {

		try {
			return client.packages()
				.upload(UploadPackageRequest.builder()
					.packageId(packageId)
					.bits(request.getResource().getInputStream())
					.build())
				.map(response -> Tuple2.of(packageId, applicationId));
		} catch (IOException e) {
			return Mono.error(e);
		}
	}

	private Mono<String> stagePackage(String packageId, String applicationId) {

		return client.packages()
				.stage(StagePackageRequest.builder()
						.packageId(packageId)
						.build())
				.map(response -> applicationId);
	}

	private Mono<String> launchApp(String applicationId) {

		return client.applicationsV3()
			.start(StartApplicationRequest.builder()
				.applicationId(applicationId)
					.build())
			.map(Application::getId);
	}

	/**
	 * Scan deploymentProperties and apply ones that start with the prefix as
	 * Cloud Foundry environmental variables minus the prefix.
	 *
	 * @param request
	 * @return a map of environment properties
	 */
	private Map<String, String> envVariables(AppDeploymentRequest request) {

		String envPrefix = "env.";

		return request.getEnvironmentProperties().entrySet().stream()
				.filter(e -> e.getKey().startsWith(envPrefix))
				.collect(Collectors.toMap(
						e -> e.getKey().substring(envPrefix.length()),
						Map.Entry::getValue));
	}

	/**
	 * See if appName exists by trying to fetch it.
	 *
	 * @param appName
	 * @return boolean state of whether or not the app exists in Cloud Foundry
	 */
	private boolean appExists(String appName) {

		try {

			long count = client.applicationsV3()
					.list(ListApplicationsRequest.builder()
						.name(appName)
						.page(1)
						.build())
					.flatMap(response -> Flux.fromIterable(response.getResources()))
					.count()
					.get(Duration.ofSeconds(10));

			if (count > 0) {
				loggers.parallelStream().forEach(logger -> logger.info("Does " + appName + " exist? Yes"));
				return true;
			} else {
				loggers.parallelStream().forEach(logger -> logger.info("Does " + appName + " exist? No"));
				return false;
			}

		} catch (HttpStatusCodeException e) {
			loggers.parallelStream().forEach(logger -> logger.info("Does " + appName + " exist? No"));
			return false;
		}
	}

	/**
	 * Find the application and delete it
	 *
	 * @param id
	 * @throws {@link IllegalStateException} if the app does NOT exist.
	 */
	@Override
	public void undeploy(String id) {

		if (appExists(id)) {

			client.applicationsV3()
					.list(ListApplicationsRequest.builder()
						.name(id)
						.page(1)
						.build())
					.flatMap(response -> Flux.fromIterable(response.getResources()))
					.single()
					.map(Application::getId)
					.map(applicationId -> client.applicationsV3()
						.delete(DeleteApplicationRequest.builder()
							.applicationId(applicationId)
							.build()))
					.get(Duration.ofSeconds(10));
		} else {
			throw new IllegalStateException(id + " is not deployed.");
		}
	}

	@Override
	public AppStatus status(String id) {

		return client.applicationsV3()
				.list(ListApplicationsRequest.builder()
					.name(id)
					.page(1)
					.build())
				.flatMap(response -> Flux.fromIterable(response.getResources()))
				.single()
				.map(Application::getId)
				.flatMap(applicationId -> client.applicationsV3()
					.getStatistics(GetApplicationStatisticsRequest.builder()
						.applicationId(applicationId)
						.build()))
				.flatMap(response -> Flux.fromIterable(response.getProcesses()))
				.map(CloudFoundryInstance::new)
				.reduce(AppStatus.of(id), AppStatus.Builder::with)
				.get(Duration.ofSeconds(10))
				.build();
	}

}
