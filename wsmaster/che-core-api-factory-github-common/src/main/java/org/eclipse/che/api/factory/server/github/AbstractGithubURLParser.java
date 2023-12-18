/*
 * Copyright (c) 2012-2023 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package org.eclipse.che.api.factory.server.github;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Strings.isNullOrEmpty;
import static java.lang.String.format;
import static java.util.regex.Pattern.compile;
import static org.eclipse.che.api.factory.server.ApiExceptionMapper.toApiException;
import static org.eclipse.che.api.factory.server.github.GithubApiClient.GITHUB_SAAS_ENDPOINT;
import static org.eclipse.che.commons.lang.StringUtils.trimEnd;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.che.api.core.ApiException;
import org.eclipse.che.api.factory.server.scm.PersonalAccessToken;
import org.eclipse.che.api.factory.server.scm.PersonalAccessTokenManager;
import org.eclipse.che.api.factory.server.scm.exception.*;
import org.eclipse.che.api.factory.server.urlfactory.DevfileFilenamesProvider;
import org.eclipse.che.commons.env.EnvironmentContext;
import org.eclipse.che.commons.subject.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parser of String Github URLs and provide {@link GithubUrl} objects.
 *
 * @author Florent Benoit
 */
public abstract class AbstractGithubURLParser {

  private static final Logger LOG = LoggerFactory.getLogger(AbstractGithubURLParser.class);
  private final PersonalAccessTokenManager tokenManager;
  private final DevfileFilenamesProvider devfileFilenamesProvider;
  private final GithubApiClient apiClient;
  private final String oauthEndpoint;
  /**
   * Regexp to find repository details (repository name, project name and branch and subfolder)
   * Examples of valid URLs are in the test class.
   */
  private final Pattern githubPattern;

  private final Pattern githubSSHPattern;

  private final boolean disableSubdomainIsolation;

  private final String providerName;

  /** Constructor used for testing only. */
  AbstractGithubURLParser(
      PersonalAccessTokenManager tokenManager,
      DevfileFilenamesProvider devfileFilenamesProvider,
      GithubApiClient githubApiClient,
      String oauthEndpoint,
      boolean disableSubdomainIsolation,
      String providerName) {
    this.tokenManager = tokenManager;
    this.devfileFilenamesProvider = devfileFilenamesProvider;
    this.apiClient = githubApiClient;
    this.oauthEndpoint = oauthEndpoint;
    this.disableSubdomainIsolation = disableSubdomainIsolation;
    this.providerName = providerName;

    String endpoint =
        isNullOrEmpty(oauthEndpoint) ? GITHUB_SAAS_ENDPOINT : trimEnd(oauthEndpoint, '/');

    this.githubPattern =
        compile(
            format(
                "^%s/(?<repoUser>[^/]+)/(?<repoName>[^/]++)((/)|(?:/tree/(?<branchName>.++))|(/pull/(?<pullRequestId>\\d++)))?$",
                endpoint));
    this.githubSSHPattern =
        compile(format("^git@%s:(?<repoUser>.*)/(?<repoName>.*)$", URI.create(endpoint).getHost()));
  }

  public boolean isValid(@NotNull String url) {
    String trimmedUrl = trimEnd(url, '/');
    return githubPattern.matcher(trimmedUrl).matches()
        || githubSSHPattern.matcher(trimmedUrl).matches();
  }

  public GithubUrl parseWithoutAuthentication(String url) throws ApiException {
    return parse(trimEnd(url, '/'), false);
  }

  public GithubUrl parse(String url) throws ApiException {
    return parse(trimEnd(url, '/'), true);
  }

  private GithubUrl parse(String url, boolean authenticationRequired) throws ApiException {
    boolean isHTTPSUrl = githubPattern.matcher(url).matches();
    Matcher matcher = isHTTPSUrl ? githubPattern.matcher(url) : githubSSHPattern.matcher(url);
    if (!matcher.matches()) {
      throw new IllegalArgumentException(
          format("The given url %s is not a valid github URL. ", url));
    }

    String serverUrl =
        isNullOrEmpty(oauthEndpoint) || trimEnd(oauthEndpoint, '/').equals(GITHUB_SAAS_ENDPOINT)
            ? null
            : trimEnd(oauthEndpoint, '/');
    String repoUser = matcher.group("repoUser");
    String repoName = matcher.group("repoName");
    if (repoName.matches("^[\\w-][\\w.-]*?\\.git$")) {
      repoName = repoName.substring(0, repoName.length() - 4);
    }

    String branchName = null;
    String pullRequestId = null;
    if (isHTTPSUrl) {
      branchName = matcher.group("branchName");
      pullRequestId = matcher.group("pullRequestId");
    }

    if (pullRequestId != null) {
      GithubPullRequest pullRequest =
          this.getPullRequest(pullRequestId, repoUser, repoName, authenticationRequired);
      if (pullRequest != null) {
        String state = pullRequest.getState();
        if (!"open".equalsIgnoreCase(state)) {
          throw new IllegalArgumentException(
              format(
                  "The given Pull Request url %s is not Opened, (found %s), thus it can't be opened as branch may have been removed.",
                  url, state));
        }

        GithubHead pullRequestHead = pullRequest.getHead();
        repoUser = pullRequestHead.getUser().getLogin();
        repoName = pullRequestHead.getRepo().getName();
        branchName = pullRequestHead.getRef();
      }
    }

    String latestCommit = null;
    GithubCommit commit =
        this.getLatestCommit(
            repoUser, repoName, firstNonNull(branchName, "HEAD"), authenticationRequired);
    if (commit != null) {
      latestCommit = commit.getSha();
    }

    return new GithubUrl(providerName)
        .withUsername(repoUser)
        .withRepository(repoName)
        .setIsHTTPSUrl(isHTTPSUrl)
        .withServerUrl(serverUrl)
        .withDisableSubdomainIsolation(disableSubdomainIsolation)
        .withBranch(branchName)
        .withLatestCommit(latestCommit)
        .withDevfileFilenames(devfileFilenamesProvider.getConfiguredDevfileFilenames())
        .withUrl(url);
  }

  private GithubPullRequest getPullRequest(
      String pullRequestId, String repoUser, String repoName, boolean authenticationRequired)
      throws ApiException {
    try {
      // prepare token
      String githubEndpoint =
          isNullOrEmpty(oauthEndpoint) ? GITHUB_SAAS_ENDPOINT : trimEnd(oauthEndpoint, '/');
      Subject subject = EnvironmentContext.getCurrent().getSubject();
      PersonalAccessToken personalAccessToken = null;
      Optional<PersonalAccessToken> token = tokenManager.get(subject, githubEndpoint);
      if (token.isPresent()) {
        personalAccessToken = token.get();
      } else if (authenticationRequired) {
        personalAccessToken = tokenManager.fetchAndSave(subject, githubEndpoint);
      }

      // get pull request
      return this.apiClient.getPullRequest(
          pullRequestId,
          repoUser,
          repoName,
          personalAccessToken != null ? personalAccessToken.getToken() : null);
    } catch (UnknownScmProviderException e) {

      // get pull request without authentication
      try {
        return this.apiClient.getPullRequest(pullRequestId, repoUser, repoName, null);
      } catch (ScmItemNotFoundException
          | ScmCommunicationException
          | ScmBadRequestException exception) {
        LOG.error("Failed to authenticate to GitHub", e);
      }

    } catch (ScmUnauthorizedException e) {
      throw toApiException(e);
    } catch (ScmCommunicationException
        | UnsatisfiedScmPreconditionException
        | ScmConfigurationPersistenceException e) {
      LOG.error("Failed to authenticate to GitHub", e);
    } catch (ScmItemNotFoundException | ScmBadRequestException e) {
      LOG.error("Failed retrieve GitHub Pull Request", e);
    }

    return null;
  }

  private GithubCommit getLatestCommit(
      String repoUser, String repoName, String branchName, boolean authenticationRequired)
      throws ApiException {
    try {
      // prepare token
      String githubEndpoint =
          isNullOrEmpty(oauthEndpoint) ? GITHUB_SAAS_ENDPOINT : trimEnd(oauthEndpoint, '/');
      Subject subject = EnvironmentContext.getCurrent().getSubject();
      PersonalAccessToken personalAccessToken = null;
      Optional<PersonalAccessToken> token = tokenManager.get(subject, githubEndpoint);
      if (token.isPresent()) {
        personalAccessToken = token.get();
      } else if (authenticationRequired) {
        personalAccessToken = tokenManager.fetchAndSave(subject, githubEndpoint);
      }

      // get latest commit
      return this.apiClient.getLatestCommit(
          repoUser,
          repoName,
          branchName,
          personalAccessToken != null ? personalAccessToken.getToken() : null);
    } catch (UnknownScmProviderException | ScmUnauthorizedException e) {
      // get latest commit without authentication
      try {
        return this.apiClient.getLatestCommit(repoUser, repoName, branchName, null);
      } catch (ScmItemNotFoundException
          | ScmCommunicationException
          | ScmBadRequestException
          | URISyntaxException exception) {
        LOG.error("Failed to authenticate to GitHub", e);
      }
    } catch (ScmCommunicationException
        | UnsatisfiedScmPreconditionException
        | ScmConfigurationPersistenceException e) {
      LOG.error("Failed to authenticate to GitHub", e);
    } catch (ScmItemNotFoundException | ScmBadRequestException | URISyntaxException e) {
      LOG.error("Failed to retrieve the latest commit", e);
      e.printStackTrace();
    }

    return null;
  }
}