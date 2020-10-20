/*
 * SonarQube
 * Copyright (C) 2009-2020 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.organization;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.apache.commons.lang.RandomStringUtils;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.sonar.api.impl.utils.TestSystem2;
import org.sonar.api.resources.Qualifiers;
import org.sonar.api.resources.ResourceTypes;
import org.sonar.api.utils.System2;
import org.sonar.api.web.UserRole;
import org.sonar.core.permission.GlobalPermissions;
import org.sonar.core.util.SequenceUuidFactory;
import org.sonar.core.util.UuidFactory;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.DbTester;
import org.sonar.db.component.ResourceTypesRule;
import org.sonar.db.organization.DefaultTemplates;
import org.sonar.db.organization.OrganizationDto;
import org.sonar.db.organization.OrganizationDto.Subscription;
import org.sonar.db.permission.template.PermissionTemplateDto;
import org.sonar.db.permission.template.PermissionTemplateGroupDto;
import org.sonar.db.qualitygate.QualityGateDto;
import org.sonar.db.qualityprofile.QProfileDto;
import org.sonar.db.qualityprofile.RulesProfileDto;
import org.sonar.db.user.GroupDto;
import org.sonar.db.user.UserDto;
import org.sonar.db.user.UserMembershipDto;
import org.sonar.db.user.UserMembershipQuery;
import org.sonar.server.es.EsTester;
import org.sonar.server.es.SearchOptions;
import org.sonar.server.permission.PermissionService;
import org.sonar.server.permission.PermissionServiceImpl;
import org.sonar.server.qualityprofile.BuiltInQProfile;
import org.sonar.server.qualityprofile.BuiltInQProfileRepositoryRule;
import org.sonar.server.qualityprofile.QProfileName;
import org.sonar.server.user.index.UserIndex;
import org.sonar.server.user.index.UserIndexer;
import org.sonar.server.user.index.UserQuery;
import org.sonar.server.usergroups.DefaultGroupCreator;
import org.sonar.server.usergroups.DefaultGroupCreatorImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.sonar.server.language.LanguageTesting.newLanguage;
import static org.sonar.server.organization.OrganizationUpdater.NewOrganization.newOrganizationBuilder;

public class OrganizationUpdaterImplTest {
  private static final long A_DATE = 12893434L;

  private OrganizationUpdater.NewOrganization FULL_POPULATED_NEW_ORGANIZATION = newOrganizationBuilder()
    .setName("a-name")
    .setKey("a-key")
    .setDescription("a-description")
    .setUrl("a-url")
    .setAvatarUrl("a-avatar")
    .build();

  private System2 system2 = new TestSystem2().setNow(A_DATE);

  private static Consumer<OrganizationDto> EMPTY_ORGANIZATION_CONSUMER = o -> {
  };

  @Rule
  public DbTester db = DbTester.create(system2);
  @Rule
  public EsTester es = EsTester.create();
  @Rule
  public ExpectedException expectedException = ExpectedException.none();
  @Rule
  public BuiltInQProfileRepositoryRule builtInQProfileRepositoryRule = new BuiltInQProfileRepositoryRule();

  private DbSession dbSession = db.getSession();

  private IllegalArgumentException exceptionThrownByOrganizationValidation = new IllegalArgumentException("simulate IAE thrown by OrganizationValidation");
  private DbClient dbClient = db.getDbClient();
  private UuidFactory uuidFactory = new SequenceUuidFactory();
  private OrganizationValidation organizationValidation = mock(OrganizationValidation.class);
  private UserIndexer userIndexer = new UserIndexer(dbClient, es.client());
  private UserIndex userIndex = new UserIndex(es.client(), system2);
  private DefaultGroupCreator defaultGroupCreator = new DefaultGroupCreatorImpl(dbClient, uuidFactory);

  private ResourceTypes resourceTypes = new ResourceTypesRule().setRootQualifiers(Qualifiers.PROJECT);
  private PermissionService permissionService = new PermissionServiceImpl(resourceTypes);

  private OrganizationUpdaterImpl underTest = new OrganizationUpdaterImpl(dbClient, system2, uuidFactory, organizationValidation, userIndexer,
    builtInQProfileRepositoryRule, defaultGroupCreator, permissionService);
  @Test
  public void visibility_public_if_not_set() throws OrganizationUpdater.KeyConflictException {
	builtInQProfileRepositoryRule.initialize();
	UserDto user = db.users().insertUser();
	db.qualityGates().insertBuiltInQualityGate();
	OrganizationDto organization = underTest.create(dbSession, user, FULL_POPULATED_NEW_ORGANIZATION, EMPTY_ORGANIZATION_CONSUMER);

    assertThat(db.organizations().getNewProjectPrivate(organization)).isFalse();
  }

  @Test
  public void visibility_public_if_true() throws OrganizationUpdater.KeyConflictException {
	builtInQProfileRepositoryRule.initialize();
	UserDto user = db.users().insertUser();
	db.qualityGates().insertBuiltInQualityGate();
	settings.setProperty(CorePropertyDefinitions.ORGANIZATIONS_DEFAULT_PUBLIC_VISIBILITY, true);
	OrganizationDto organization = underTest.create(dbSession, user, FULL_POPULATED_NEW_ORGANIZATION, EMPTY_ORGANIZATION_CONSUMER);

    assertThat(db.organizations().getNewProjectPrivate(organization)).isFalse();
  }
  
  @Test
  public void visibility_public_if_false() throws OrganizationUpdater.KeyConflictException {
	builtInQProfileRepositoryRule.initialize();
	UserDto user = db.users().insertUser();
	db.qualityGates().insertBuiltInQualityGate();
	settings.setProperty(CorePropertyDefinitions.ORGANIZATIONS_DEFAULT_PUBLIC_VISIBILITY, false);
	OrganizationDto organization = underTest.create(dbSession, user, FULL_POPULATED_NEW_ORGANIZATION, EMPTY_ORGANIZATION_CONSUMER);
	
    assertThat(db.organizations().getNewProjectPrivate(organization)).isTrue();
  }


  @Test
  public void create_creates_organization_with_properties_from_NewOrganization_arg() throws OrganizationUpdater.KeyConflictException {
    builtInQProfileRepositoryRule.initialize();
    UserDto user = db.users().insertUser();
    db.qualityGates().insertBuiltInQualityGate();

    underTest.create(dbSession, user, FULL_POPULATED_NEW_ORGANIZATION, EMPTY_ORGANIZATION_CONSUMER);

    OrganizationDto organization = dbClient.organizationDao().selectByKey(dbSession, FULL_POPULATED_NEW_ORGANIZATION.getKey()).get();
    assertThat(organization.getUuid()).isNotEmpty();
    assertThat(organization.getKey()).isEqualTo(FULL_POPULATED_NEW_ORGANIZATION.getKey());
    assertThat(organization.getName()).isEqualTo(FULL_POPULATED_NEW_ORGANIZATION.getName());
    assertThat(organization.getDescription()).isEqualTo(FULL_POPULATED_NEW_ORGANIZATION.getDescription());
    assertThat(organization.getUrl()).isEqualTo(FULL_POPULATED_NEW_ORGANIZATION.getUrl());
    assertThat(organization.getAvatarUrl()).isEqualTo(FULL_POPULATED_NEW_ORGANIZATION.getAvatar());
    assertThat(organization.getSubscription()).isEqualTo(Subscription.FREE);
    assertThat(organization.getCreatedAt()).isEqualTo(A_DATE);
    assertThat(organization.getUpdatedAt()).isEqualTo(A_DATE);
  }

  @Test
  public void create_creates_owners_group_with_all_permissions_for_new_organization_and_add_current_user_to_it() throws OrganizationUpdater.KeyConflictException {
    UserDto user = db.users().insertUser();
    builtInQProfileRepositoryRule.initialize();
    db.qualityGates().insertBuiltInQualityGate();

    underTest.create(dbSession, user, FULL_POPULATED_NEW_ORGANIZATION, EMPTY_ORGANIZATION_CONSUMER);

    verifyGroupOwners(user, FULL_POPULATED_NEW_ORGANIZATION.getKey(), FULL_POPULATED_NEW_ORGANIZATION.getName());
  }

  @Test
  public void create_creates_members_group_and_add_current_user_to_it() throws OrganizationUpdater.KeyConflictException {
    UserDto user = db.users().insertUser();
    builtInQProfileRepositoryRule.initialize();
    db.qualityGates().insertBuiltInQualityGate();

    underTest.create(dbSession, user, FULL_POPULATED_NEW_ORGANIZATION, EMPTY_ORGANIZATION_CONSUMER);

    verifyMembersGroup(user, FULL_POPULATED_NEW_ORGANIZATION.getKey());
  }

  @Test
  public void create_does_not_require_description_url_and_avatar_to_be_non_null() throws OrganizationUpdater.KeyConflictException {
    builtInQProfileRepositoryRule.initialize();
    UserDto user = db.users().insertUser();
    db.qualityGates().insertBuiltInQualityGate();

    underTest.create(dbSession, user, newOrganizationBuilder()
      .setKey("key")
      .setName("name")
      .build(), EMPTY_ORGANIZATION_CONSUMER);

    OrganizationDto organization = dbClient.organizationDao().selectByKey(dbSession, "key").get();
    assertThat(organization.getKey()).isEqualTo("key");
    assertThat(organization.getName()).isEqualTo("name");
    assertThat(organization.getDescription()).isNull();
    assertThat(organization.getUrl()).isNull();
    assertThat(organization.getAvatarUrl()).isNull();
  }

  @Test
  public void create_creates_default_template_for_new_organization() throws OrganizationUpdater.KeyConflictException {
    builtInQProfileRepositoryRule.initialize();
    UserDto user = db.users().insertUser();
    db.qualityGates().insertBuiltInQualityGate();

    underTest.create(dbSession, user, FULL_POPULATED_NEW_ORGANIZATION, EMPTY_ORGANIZATION_CONSUMER);

    OrganizationDto organization = dbClient.organizationDao().selectByKey(dbSession, FULL_POPULATED_NEW_ORGANIZATION.getKey()).get();
    GroupDto ownersGroup = dbClient.groupDao().selectByName(dbSession, organization.getUuid(), "Owners").get();
    String defaultGroupUuid = dbClient.organizationDao().getDefaultGroupUuid(dbSession, organization.getUuid()).get();
    PermissionTemplateDto defaultTemplate = dbClient.permissionTemplateDao().selectByName(dbSession, organization.getUuid(), "default template");
    assertThat(defaultTemplate.getName()).isEqualTo("Default template");
    assertThat(defaultTemplate.getDescription()).isEqualTo("Default permission template of organization " + FULL_POPULATED_NEW_ORGANIZATION.getName());
    DefaultTemplates defaultTemplates = dbClient.organizationDao().getDefaultTemplates(dbSession, organization.getUuid()).get();
    assertThat(defaultTemplates.getProjectUuid()).isEqualTo(defaultTemplate.getUuid());
    assertThat(defaultTemplates.getApplicationsUuid()).isNull();
    assertThat(dbClient.permissionTemplateDao().selectGroupPermissionsByTemplateUuid(dbSession, defaultTemplate.getUuid()))
      .extracting(PermissionTemplateGroupDto::getGroupUuid, PermissionTemplateGroupDto::getPermission)
      .containsOnly(
        tuple(ownersGroup.getUuid(), UserRole.ADMIN),
        tuple(ownersGroup.getUuid(), GlobalPermissions.SCAN_EXECUTION),
        tuple(defaultGroupUuid, UserRole.USER),
        tuple(defaultGroupUuid, UserRole.CODEVIEWER),
        tuple(defaultGroupUuid, UserRole.ISSUE_ADMIN),
        tuple(defaultGroupUuid, UserRole.SECURITYHOTSPOT_ADMIN));
  }

  @Test
  public void create_add_current_user_as_member_of_organization() throws OrganizationUpdater.KeyConflictException {
    UserDto user = db.users().insertUser();
    builtInQProfileRepositoryRule.initialize();
    db.qualityGates().insertBuiltInQualityGate();

    OrganizationDto result = underTest.create(dbSession, user, FULL_POPULATED_NEW_ORGANIZATION, EMPTY_ORGANIZATION_CONSUMER);

    assertThat(dbClient.organizationMemberDao().select(dbSession, result.getUuid(), user.getUuid())).isPresent();
    assertThat(userIndex.search(UserQuery.builder().setOrganizationUuid(result.getUuid()).setTextQuery(user.getLogin()).build(), new SearchOptions()).getTotal()).isEqualTo(1L);
  }

  @Test
  public void create_associates_to_built_in_quality_profiles() throws OrganizationUpdater.KeyConflictException {
    BuiltInQProfile builtIn1 = builtInQProfileRepositoryRule.add(newLanguage("foo"), "qp1", true);
    BuiltInQProfile builtIn2 = builtInQProfileRepositoryRule.add(newLanguage("foo"), "qp2");
    builtInQProfileRepositoryRule.initialize();
    insertRulesProfile(builtIn1);
    insertRulesProfile(builtIn2);
    UserDto user = db.users().insertUser();
    db.qualityGates().insertBuiltInQualityGate();

    underTest.create(dbSession, user, FULL_POPULATED_NEW_ORGANIZATION, EMPTY_ORGANIZATION_CONSUMER);

    OrganizationDto organization = dbClient.organizationDao().selectByKey(dbSession, FULL_POPULATED_NEW_ORGANIZATION.getKey()).get();
    List<QProfileDto> profiles = dbClient.qualityProfileDao().selectOrderedByOrganizationUuid(dbSession, organization);
    assertThat(profiles).extracting(p -> new QProfileName(p.getLanguage(), p.getName())).containsExactlyInAnyOrder(
      builtIn1.getQProfileName(), builtIn2.getQProfileName());
    assertThat(dbClient.qualityProfileDao().selectDefaultProfile(dbSession, organization, "foo").getName())
      .isEqualTo("qp1");
  }

  private void insertRulesProfile(BuiltInQProfile builtIn) {
    RulesProfileDto dto = new RulesProfileDto()
      .setIsBuiltIn(true)
      .setUuid(RandomStringUtils.randomAlphabetic(40))
      .setLanguage(builtIn.getLanguage())
      .setName(builtIn.getName());
    dbClient.qualityProfileDao().insert(db.getSession(), dto);
    db.commit();
  }

  @Test
  public void create_associates_to_built_in_quality_gate() throws OrganizationUpdater.KeyConflictException {
    QualityGateDto builtInQualityGate = db.qualityGates().insertBuiltInQualityGate();
    builtInQProfileRepositoryRule.initialize();
    UserDto user = db.users().insertUser();

    underTest.create(dbSession, user, FULL_POPULATED_NEW_ORGANIZATION, o -> {
    });

    OrganizationDto organization = dbClient.organizationDao().selectByKey(dbSession, FULL_POPULATED_NEW_ORGANIZATION.getKey()).get();
    assertThat(dbClient.qualityGateDao().selectDefault(dbSession, organization).getUuid()).isEqualTo(builtInQualityGate.getUuid());
  }

  @Test
  public void create_calls_consumer() throws OrganizationUpdater.KeyConflictException {
    UserDto user = db.users().insertUser();
    builtInQProfileRepositoryRule.initialize();
    db.qualityGates().insertBuiltInQualityGate();
    Boolean[] isConsumerCalled = new Boolean[]{false};

    underTest.create(dbSession, user, FULL_POPULATED_NEW_ORGANIZATION, o -> {
      isConsumerCalled[0] = true;
    });

    assertThat(isConsumerCalled[0]).isEqualTo(true);
  }

  @Test
  public void create_throws_NPE_if_NewOrganization_arg_is_null() throws OrganizationUpdater.KeyConflictException {
    UserDto user = db.users().insertUser();

    expectedException.expect(NullPointerException.class);
    expectedException.expectMessage("newOrganization can't be null");

    underTest.create(dbSession, user, null, EMPTY_ORGANIZATION_CONSUMER);
  }

  @Test
  public void create_throws_exception_thrown_by_checkValidKey() throws OrganizationUpdater.KeyConflictException {
    UserDto user = db.users().insertUser();

    when(organizationValidation.checkKey(FULL_POPULATED_NEW_ORGANIZATION.getKey()))
      .thenThrow(exceptionThrownByOrganizationValidation);

    createThrowsExceptionThrownByOrganizationValidation(user);
  }

  @Test
  public void create_throws_exception_thrown_by_checkValidDescription() throws OrganizationUpdater.KeyConflictException {
    UserDto user = db.users().insertUser();

    when(organizationValidation.checkDescription(FULL_POPULATED_NEW_ORGANIZATION.getDescription())).thenThrow(exceptionThrownByOrganizationValidation);

    createThrowsExceptionThrownByOrganizationValidation(user);
  }

  @Test
  public void create_throws_exception_thrown_by_checkValidUrl() throws OrganizationUpdater.KeyConflictException {
    UserDto user = db.users().insertUser();

    when(organizationValidation.checkUrl(FULL_POPULATED_NEW_ORGANIZATION.getUrl())).thenThrow(exceptionThrownByOrganizationValidation);

    createThrowsExceptionThrownByOrganizationValidation(user);
  }

  @Test
  public void create_throws_exception_thrown_by_checkValidAvatar() throws OrganizationUpdater.KeyConflictException {
    UserDto user = db.users().insertUser();

    when(organizationValidation.checkAvatar(FULL_POPULATED_NEW_ORGANIZATION.getAvatar())).thenThrow(exceptionThrownByOrganizationValidation);

    createThrowsExceptionThrownByOrganizationValidation(user);
  }

  private void createThrowsExceptionThrownByOrganizationValidation(UserDto user) throws OrganizationUpdater.KeyConflictException {
    try {
      underTest.create(dbSession, user, FULL_POPULATED_NEW_ORGANIZATION, EMPTY_ORGANIZATION_CONSUMER);
      fail(exceptionThrownByOrganizationValidation + " should have been thrown");
    } catch (IllegalArgumentException e) {
      assertThat(e).isSameAs(exceptionThrownByOrganizationValidation);
    }
  }

  @Test
  public void create_fails_with_KeyConflictException_if_org_with_key_in_NewOrganization_arg_already_exists_in_db() throws OrganizationUpdater.KeyConflictException {
    db.organizations().insertForKey(FULL_POPULATED_NEW_ORGANIZATION.getKey());
    UserDto user = db.users().insertUser();

    expectedException.expect(OrganizationUpdater.KeyConflictException.class);
    expectedException.expectMessage("Organization key '" + FULL_POPULATED_NEW_ORGANIZATION.getKey() + "' is already used");

    underTest.create(dbSession, user, FULL_POPULATED_NEW_ORGANIZATION, EMPTY_ORGANIZATION_CONSUMER);
  }

  @Test
  public void update_personal_organization() {
    OrganizationDto organization = db.organizations().insert(o -> o.setKey("old login"));
    when(organizationValidation.generateKeyFrom("new_login")).thenReturn("new_login");

    underTest.updateOrganizationKey(dbSession, organization, "new_login");

    OrganizationDto organizationReloaded = dbClient.organizationDao().selectByUuid(dbSession, organization.getUuid()).get();
    assertThat(organizationReloaded.getKey()).isEqualTo("new_login");
  }

  @Test
  public void does_not_update_personal_organization_when_generated_organization_key_does_not_change() {
    OrganizationDto organization = db.organizations().insert(o -> o.setKey("login"));
    when(organizationValidation.generateKeyFrom("Login")).thenReturn("login");

    underTest.updateOrganizationKey(dbSession, organization, "Login");

    OrganizationDto organizationReloaded = dbClient.organizationDao().selectByUuid(dbSession, organization.getUuid()).get();
    assertThat(organizationReloaded.getKey()).isEqualTo("login");
  }

  @Test
  public void fail_to_update_personal_organization_when_new_key_already_exist() {
    OrganizationDto organization = db.organizations().insert();
    db.organizations().insert(o -> o.setKey("new_login"));
    when(organizationValidation.generateKeyFrom("new_login")).thenReturn("new_login");

    expectedException.expect(IllegalStateException.class);
    expectedException.expectMessage("Can't create organization with key 'new_login' because an organization with this key already exists");

    underTest.updateOrganizationKey(dbSession, organization, "new_login");
  }

  private void verifyGroupOwners(UserDto user, String organizationKey, String organizationName) {
    OrganizationDto organization = dbClient.organizationDao().selectByKey(dbSession, organizationKey).get();
    Optional<GroupDto> groupOpt = dbClient.groupDao().selectByName(dbSession, organization.getUuid(), "Owners");
    assertThat(groupOpt).isPresent();
    GroupDto groupDto = groupOpt.get();
    assertThat(groupDto.getDescription()).isEqualTo("Owners of organization");

    assertThat(dbClient.groupPermissionDao().selectGlobalPermissionsOfGroup(dbSession, groupDto.getOrganizationUuid(), groupDto.getUuid()))
      .containsOnly(GlobalPermissions.ALL.toArray(new String[GlobalPermissions.ALL.size()]));
    List<UserMembershipDto> members = dbClient.groupMembershipDao().selectMembers(
      dbSession,
      UserMembershipQuery.builder()
        .organizationUuid(organization.getUuid())
        .groupUuid(groupDto.getUuid())
        .membership(UserMembershipQuery.IN).build(),
      0, Integer.MAX_VALUE);
    assertThat(members)
      .extracting(UserMembershipDto::getLogin)
      .containsOnly(user.getLogin());
  }

  private void verifyMembersGroup(UserDto user, String organizationKey) {
    OrganizationDto organization = dbClient.organizationDao().selectByKey(dbSession, organizationKey).get();
    Optional<GroupDto> groupOpt = dbClient.groupDao().selectByName(dbSession, organization.getUuid(), "Members");
    assertThat(groupOpt).isPresent();
    GroupDto groupDto = groupOpt.get();
    assertThat(groupDto.getDescription()).isEqualTo("All members of the organization");

    assertThat(dbClient.groupPermissionDao().selectGlobalPermissionsOfGroup(dbSession, groupDto.getOrganizationUuid(), groupDto.getUuid())).isEmpty();
    List<UserMembershipDto> members = dbClient.groupMembershipDao().selectMembers(
      dbSession,
      UserMembershipQuery.builder()
        .organizationUuid(organization.getUuid())
        .groupUuid(groupDto.getUuid())
        .membership(UserMembershipQuery.IN).build(),
      0, Integer.MAX_VALUE);
    assertThat(members)
      .extracting(UserMembershipDto::getLogin)
      .containsOnly(user.getLogin());
  }

}
