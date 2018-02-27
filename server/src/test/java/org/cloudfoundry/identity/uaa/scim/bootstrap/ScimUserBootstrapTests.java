/*******************************************************************************
 *     Cloud Foundry
 *     Copyright (c) [2009-2016] Pivotal Software, Inc. All Rights Reserved.
 *
 *     This product is licensed to you under the Apache License, Version 2.0 (the "License").
 *     You may not use this product except in compliance with the License.
 *
 *     This product includes a number of subcomponents with
 *     separate copyright notices and license terms. Your use of these
 *     subcomponents is subject to the terms and conditions of the
 *     subcomponent's license, as noted in the LICENSE file.
 *******************************************************************************/
package org.cloudfoundry.identity.uaa.scim.bootstrap;

import org.cloudfoundry.identity.uaa.audit.event.EntityDeletedEvent;
import org.cloudfoundry.identity.uaa.authentication.manager.ExternalGroupAuthorizationEvent;
import org.cloudfoundry.identity.uaa.authentication.manager.InvitedUserAuthenticatedEvent;
import org.cloudfoundry.identity.uaa.constants.OriginKeys;
import org.cloudfoundry.identity.uaa.resources.jdbc.JdbcPagingListFactory;
import org.cloudfoundry.identity.uaa.resources.jdbc.LimitSqlAdapterFactory;
import org.cloudfoundry.identity.uaa.scim.ScimGroup;
import org.cloudfoundry.identity.uaa.scim.ScimGroupMember;
import org.cloudfoundry.identity.uaa.scim.ScimUser;
import org.cloudfoundry.identity.uaa.scim.endpoints.ScimUserEndpoints;
import org.cloudfoundry.identity.uaa.scim.exception.InvalidPasswordException;
import org.cloudfoundry.identity.uaa.scim.exception.MemberNotFoundException;
import org.cloudfoundry.identity.uaa.scim.jdbc.JdbcScimGroupMembershipManager;
import org.cloudfoundry.identity.uaa.scim.jdbc.JdbcScimGroupProvisioning;
import org.cloudfoundry.identity.uaa.scim.jdbc.JdbcScimUserProvisioning;
import org.cloudfoundry.identity.uaa.test.JdbcTestBase;
import org.cloudfoundry.identity.uaa.user.UaaUser;
import org.cloudfoundry.identity.uaa.zone.IdentityZone;
import org.cloudfoundry.identity.uaa.zone.IdentityZoneHolder;
import org.cloudfoundry.identity.uaa.zone.MultitenancyFixture;

import org.hamcrest.collection.IsArrayContainingInAnyOrder;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.common.util.RandomValueStringGenerator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static java.util.Collections.emptyList;
import static java.util.Optional.ofNullable;

public class ScimUserBootstrapTests extends JdbcTestBase {

    private JdbcScimUserProvisioning db;

    private JdbcScimGroupProvisioning gdb;

    private JdbcScimGroupMembershipManager mdb;

    private ScimUserEndpoints userEndpoints;

    private IdentityZone otherZone;

    @Before
    public void init() throws Exception {
        JdbcPagingListFactory pagingListFactory = new JdbcPagingListFactory(jdbcTemplate, LimitSqlAdapterFactory.getLimitSqlAdapter());
        db = spy(new JdbcScimUserProvisioning(jdbcTemplate, pagingListFactory));
        gdb = new JdbcScimGroupProvisioning(jdbcTemplate, pagingListFactory);
        mdb = new JdbcScimGroupMembershipManager(jdbcTemplate);
        mdb.setScimUserProvisioning(db);
        mdb.setScimGroupProvisioning(gdb);
        userEndpoints = new ScimUserEndpoints();
        userEndpoints.setScimGroupMembershipManager(mdb);
        userEndpoints.setScimUserProvisioning(db);
        String zoneId = new RandomValueStringGenerator().generate().toLowerCase();
        otherZone = MultitenancyFixture.identityZone(zoneId, zoneId);
    }

    public static void addIdentityProvider(JdbcTemplate jdbcTemplate, String originKey) {
        jdbcTemplate.update("insert into identity_provider (id,identity_zone_id,name,origin_key,type) values (?,'uaa',?,?,'UNKNOWN')", UUID.randomUUID().toString(), originKey, originKey);
    }


    @Test
    public void can_delete_users_but_only_in_default_zone() throws Exception {
        canAddUsers(OriginKeys.UAA, IdentityZone.getUaa().getId());
        canAddUsers(OriginKeys.LDAP, IdentityZone.getUaa().getId());
        canAddUsers(OriginKeys.UAA, otherZone.getId()); //this is just an update of the same two users, zoneId is ignored
        List<ScimUser> users = db.retrieveAll(IdentityZoneHolder.get().getId());
        assertEquals(4, users.size());
        reset(db);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        doAnswer(invocation -> {
            EntityDeletedEvent event = invocation.getArgument(0);
            db.deleteByUser(event.getObjectId(), IdentityZone.getUaa().getId());
            return null;
        })
            .when(publisher).publishEvent(any(EntityDeletedEvent.class));

        ScimUserBootstrap bootstrap = new ScimUserBootstrap(db, gdb, mdb, emptyList());
        List<String> usersToDelete = Arrays.asList("joe", "mabel", "non-existent");
        bootstrap.setUsersToDelete(usersToDelete);
        bootstrap.setApplicationEventPublisher(publisher);
        bootstrap.afterPropertiesSet();
        bootstrap.onApplicationEvent(mock(ContextRefreshedEvent.class));
        ArgumentCaptor<ApplicationEvent> captor = ArgumentCaptor.forClass(EntityDeletedEvent.class);
        verify(publisher, times(2)).publishEvent(captor.capture());
        List<EntityDeletedEvent<ScimUser>> deleted = new LinkedList(ofNullable(captor.getAllValues()).orElse(emptyList()));
        assertNotNull(deleted);
        assertEquals(2, deleted.size());
        deleted.forEach(event -> assertEquals(OriginKeys.UAA, event.getDeleted().getOrigin()));
        assertEquals(2, db.retrieveAll(IdentityZoneHolder.get().getId()).size());
    }


    @Test
    public void slated_for_delete_does_not_add() throws Exception {
        UaaUser joe = new UaaUser("joe", "password", "joe@test.org", "Joe", "User");
        UaaUser mabel = new UaaUser("mabel", "password", "mabel@blah.com", "Mabel", "User");
        ScimUserBootstrap bootstrap = new ScimUserBootstrap(db, gdb, mdb, Arrays.asList(joe, mabel));
        bootstrap.setUsersToDelete(Arrays.asList("joe", "mabel"));
        bootstrap.afterPropertiesSet();
        String zoneId = IdentityZoneHolder.get().getId();
        verify(db, never()).create(any(), eq(zoneId));
        Collection<ScimUser> users = db.retrieveAll(zoneId);
        assertEquals(0, users.size());
    }

    @Test
    public void canAddUsers() throws Exception {
        canAddUsers(OriginKeys.UAA, IdentityZone.getUaa().getId());
        Collection<ScimUser> users = db.retrieveAll(IdentityZoneHolder.get().getId());
        assertEquals(2, users.size());
    }

    public void canAddUsers(String origin, String zoneId) throws Exception {
        UaaUser joe = new UaaUser("joe", "password", "joe@test.org", "Joe", "User", origin, zoneId);
        UaaUser mabel = new UaaUser("mabel", "password", "mabel@blah.com", "Mabel", "User", origin, zoneId);
        ScimUserBootstrap bootstrap = new ScimUserBootstrap(db, gdb, mdb, Arrays.asList(joe, mabel));
        bootstrap.afterPropertiesSet();
    }

    @Test
    public void addedUsersAreVerified() throws Exception {
        UaaUser uaaJoe = new UaaUser("joe", "password", "joe@test.org", "Joe", "User");
        ScimUserBootstrap bootstrap = new ScimUserBootstrap(db, gdb, mdb, Arrays.asList(uaaJoe));

        bootstrap.afterPropertiesSet();

        List<ScimUser> users = db.retrieveAll(IdentityZoneHolder.get().getId());

        ScimUser scimJoe = users.get(0);
        assertTrue(scimJoe.isVerified());
    }

    @Test
    public void canAddUserWithAuthorities() throws Exception {
        UaaUser joe = new UaaUser("joe", "password", "joe@test.org", "Joe", "User");
        joe = joe.authorities(AuthorityUtils.commaSeparatedStringToAuthorityList("openid,read"));
        ScimUserBootstrap bootstrap = new ScimUserBootstrap(db, gdb, mdb, Arrays.asList(joe));
        bootstrap.afterPropertiesSet();
        @SuppressWarnings("unchecked")
        Collection<Map<String, Object>> users = (Collection<Map<String, Object>>) userEndpoints.findUsers("id",
                        "id pr", "id", "ascending", 1, 100).getResources();
        assertEquals(1, users.size());

        String id = (String) users.iterator().next().get("id");
        ScimUser user = userEndpoints.getUser(id, new MockHttpServletResponse());
        // uaa.user is always added
        assertEquals(3, user.getGroups().size());
    }

    @Test(expected = InvalidPasswordException.class)
    public void cannotAddUserWithNoPassword() throws Exception {
        UaaUser joe = new UaaUser("joe", "", "joe@test.org", "Joe", "User", OriginKeys.UAA, null);
        joe = joe.authorities(AuthorityUtils.commaSeparatedStringToAuthorityList("openid,read"));
        ScimUserBootstrap bootstrap = new ScimUserBootstrap(db, gdb, mdb, Arrays.asList(joe));
        bootstrap.afterPropertiesSet();
    }

    @Test
    public void noOverrideByDefault() throws Exception {
        UaaUser joe = new UaaUser("joe", "password", "joe@test.org", "Joe", "User");
        joe = joe.authorities(AuthorityUtils.commaSeparatedStringToAuthorityList("openid,read"));
        ScimUserBootstrap bootstrap = new ScimUserBootstrap(db, gdb, mdb, Arrays.asList(joe));
        bootstrap.afterPropertiesSet();
        joe = new UaaUser("joe", "password", "joe@test.org", "Joel", "User");
        bootstrap = new ScimUserBootstrap(db, gdb, mdb, Arrays.asList(joe));
        bootstrap.afterPropertiesSet();
        @SuppressWarnings("unchecked")
        Collection<Map<String, Object>> users = (Collection<Map<String, Object>>) userEndpoints.findUsers("id",
                        "id pr", "id", "ascending", 1, 100).getResources();
        assertEquals(1, users.size());

        String id = (String) users.iterator().next().get("id");
        ScimUser user = userEndpoints.getUser(id, new MockHttpServletResponse());
        // uaa.user is always added
        assertEquals("Joe", user.getGivenName());
    }

    @Test
    public void canOverride() throws Exception {
        UaaUser joe = new UaaUser("joe", "password", "joe@test.org", "Joe", "User");
        joe = joe.authorities(AuthorityUtils.commaSeparatedStringToAuthorityList("openid,read"));
        ScimUserBootstrap bootstrap = new ScimUserBootstrap(db, gdb, mdb, Arrays.asList(joe));
        bootstrap.afterPropertiesSet();
        joe = new UaaUser("joe", "password", "joe@test.org", "Joel", "User");
        bootstrap = new ScimUserBootstrap(db, gdb, mdb, Arrays.asList(joe));
        bootstrap.setOverride(true);
        bootstrap.afterPropertiesSet();
        @SuppressWarnings("unchecked")
        Collection<Map<String, Object>> users = (Collection<Map<String, Object>>) userEndpoints.findUsers("id",
                        "id pr", "id", "ascending", 1, 100).getResources();
        assertEquals(1, users.size());

        String id = (String) users.iterator().next().get("id");
        ScimUser user = userEndpoints.getUser(id, new MockHttpServletResponse());
        // uaa.user is always added
        assertEquals("Joel", user.getGivenName());
    }

    @Test
    public void canOverrideAuthorities() throws Exception {
        UaaUser joe = new UaaUser("joe", "password", "joe@test.org", "Joe", "User");
        joe = joe.authorities(AuthorityUtils.commaSeparatedStringToAuthorityList("openid,read"));
        ScimUserBootstrap bootstrap = new ScimUserBootstrap(db, gdb, mdb, Arrays.asList(joe));
        bootstrap.afterPropertiesSet();
        joe = joe.authorities(AuthorityUtils.commaSeparatedStringToAuthorityList("openid,read,write"));
        bootstrap = new ScimUserBootstrap(db, gdb, mdb, Arrays.asList(joe));
        bootstrap.setOverride(true);
        bootstrap.afterPropertiesSet();
        @SuppressWarnings("unchecked")
        Collection<Map<String, Object>> users = (Collection<Map<String, Object>>) userEndpoints.findUsers("id",
                        "id pr", "id", "ascending", 1, 100).getResources();
        assertEquals(1, users.size());

        String id = (String) users.iterator().next().get("id");
        ScimUser user = userEndpoints.getUser(id, new MockHttpServletResponse());
        // uaa.user is always added
        assertEquals(4, user.getGroups().size());
    }

    @Test
    public void canRemoveAuthorities() throws Exception {
        UaaUser joe = new UaaUser("joe", "password", "joe@test.org", "Joe", "User");
        joe = joe.authorities(AuthorityUtils.commaSeparatedStringToAuthorityList("openid,read"));
        ScimUserBootstrap bootstrap = new ScimUserBootstrap(db, gdb, mdb, Arrays.asList(joe));
        bootstrap.afterPropertiesSet();
        joe = joe.authorities(AuthorityUtils.commaSeparatedStringToAuthorityList("openid"));
        System.err.println(jdbcTemplate.queryForList("SELECT * FROM group_membership"));
        bootstrap = new ScimUserBootstrap(db, gdb, mdb, Arrays.asList(joe));
        bootstrap.setOverride(true);
        bootstrap.afterPropertiesSet();
        @SuppressWarnings("unchecked")
        Collection<Map<String, Object>> users = (Collection<Map<String, Object>>) userEndpoints.findUsers("id",
                        "id pr", "id", "ascending", 1, 100).getResources();
        assertEquals(1, users.size());

        String id = (String) users.iterator().next().get("id");
        ScimUser user = userEndpoints.getUser(id, new MockHttpServletResponse());
        // uaa.user is always added
        assertEquals(2, user.getGroups().size());
    }

    @Test
    public void canUpdateUsers() throws Exception {
        UaaUser joe = new UaaUser("joe", "password", "joe@test.org", "Joe", "User");
        joe = joe.modifyOrigin(OriginKeys.UAA);
        ScimUserBootstrap bootstrap = new ScimUserBootstrap(db, gdb, mdb, Arrays.asList(joe));
        bootstrap.afterPropertiesSet();

        String passwordHash = jdbcTemplate.queryForObject("select password from users where username='joe'",new Object[0], String.class);

        joe = new UaaUser("joe", "new", "joe@test.org", "Joe", "Bloggs");
        joe = joe.modifyOrigin(OriginKeys.UAA);
        bootstrap = new ScimUserBootstrap(db, gdb, mdb, Arrays.asList(joe));
        bootstrap.setOverride(true);
        bootstrap.afterPropertiesSet();
        Collection<ScimUser> users = db.retrieveAll(IdentityZoneHolder.get().getId());
        assertEquals(1, users.size());
        assertEquals("Bloggs", users.iterator().next().getFamilyName());
        assertNotEquals(passwordHash, jdbcTemplate.queryForObject("select password from users where username='joe'", new Object[0], String.class));

        passwordHash = jdbcTemplate.queryForObject("select password from users where username='joe'",new Object[0], String.class);
        bootstrap.afterPropertiesSet();
        assertEquals(passwordHash, jdbcTemplate.queryForObject("select password from users where username='joe'", new Object[0], String.class));
    }

    @Test
    public void failedAttemptToUpdateUsersNotFatal() throws Exception {
        UaaUser joe = new UaaUser("joe", "password", "joe@test.org", "Joe", "User");
        ScimUserBootstrap bootstrap = new ScimUserBootstrap(db, gdb, mdb, Arrays.asList(joe));
        bootstrap.afterPropertiesSet();
        joe = new UaaUser("joe", "new", "joe@test.org", "Joe", "Bloggs");
        bootstrap = new ScimUserBootstrap(db, gdb, mdb, Arrays.asList(joe));
        bootstrap.setOverride(false);
        bootstrap.afterPropertiesSet();
        Collection<ScimUser> users = db.retrieveAll(IdentityZoneHolder.get().getId());
        assertEquals(1, users.size());
        assertEquals("User", users.iterator().next().getFamilyName());
    }

    @Test
    public void updateUserWithEmptyPasswordDoesNotChangePassword() throws Exception {
        UaaUser joe = new UaaUser("joe", "password", "joe@test.org", "Joe", "User");
        joe = joe.modifyOrigin(OriginKeys.UAA);
        ScimUserBootstrap bootstrap = new ScimUserBootstrap(db, gdb, mdb, Arrays.asList(joe));
        bootstrap.afterPropertiesSet();

        String passwordHash = jdbcTemplate.queryForObject("select password from users where username='joe'",new Object[0], String.class);

        joe = new UaaUser("joe", "", "joe@test.org", "Joe", "Bloggs");
        joe = joe.modifyOrigin(OriginKeys.UAA);
        bootstrap = new ScimUserBootstrap(db, gdb, mdb, Arrays.asList(joe));
        bootstrap.setOverride(true);
        bootstrap.afterPropertiesSet();
        Collection<ScimUser> users = db.retrieveAll(IdentityZoneHolder.get().getId());
        assertEquals(1, users.size());
        assertEquals("Bloggs", users.iterator().next().getFamilyName());
        assertEquals(passwordHash, jdbcTemplate.queryForObject("select password from users where username='joe'", new Object[0], String.class));
    }

    @Test
    public void invited_user_gets_verified_set_to_true() throws Exception {
        String origin = "testOrigin";
        addIdentityProvider(jdbcTemplate,origin);
        String email = "test@test.org";
        String firstName = "FirstName";
        String lastName = "LastName";
        String password = "testPassword";
        String externalId = null;

        String username = new RandomValueStringGenerator().generate().toLowerCase();
        UaaUser user = getUaaUser(new String[0], origin, email, firstName, lastName, password, externalId, "not-used-id", username);
        ScimUserBootstrap bootstrap = new ScimUserBootstrap(db, gdb, mdb, Arrays.asList(user));
        bootstrap.afterPropertiesSet();

        ScimUser existingUser = db.retrieveAll(IdentityZone.getUaa().getId())
            .stream()
            .filter(u -> username.equals(u.getUserName()))
            .findFirst()
            .get();
        String userId = existingUser.getId();
        existingUser.setVerified(false);
        existingUser = db.update(userId, existingUser, IdentityZone.getUaa().getId());
        InvitedUserAuthenticatedEvent event = new InvitedUserAuthenticatedEvent(user);
        bootstrap.onApplicationEvent(event);
        ScimUser modifiedUser = db.retrieve(userId, IdentityZone.getUaa().getId());

        assertTrue(modifiedUser.isVerified());
        assertFalse(existingUser.isVerified());
    }

    @Test
    public void canAddNonExistentGroupThroughEvent() throws Exception {
        nonExistentGroupThroughEvent(true);
    }
    public void nonExistentGroupThroughEvent(boolean add) throws Exception {
        String[] externalAuthorities = new String[] {"extTest1","extTest2","extTest3"};
        String[] userAuthorities = new String[] {"usrTest1","usrTest2","usrTest3"};
        String origin = "testOrigin";
        addIdentityProvider(jdbcTemplate,origin);
        String email = "test@test.org";
        String firstName = "FirstName";
        String lastName = "LastName";
        String password = "testPassword";
        String externalId = null;
        String userId = new RandomValueStringGenerator().generate();
        String username = new RandomValueStringGenerator().generate();
        UaaUser user = getUaaUser(userAuthorities, origin, email, firstName, lastName, password, externalId, userId, username);
        ScimUserBootstrap bootstrap = new ScimUserBootstrap(db, gdb, mdb, Arrays.asList(user));
        bootstrap.afterPropertiesSet();

        List<ScimUser> users = db.query("userName eq \""+username +"\" and origin eq \""+origin+"\"", IdentityZoneHolder.get().getId());
        assertEquals(1, users.size());
        userId = users.get(0).getId();
        user = getUaaUser(userAuthorities, origin, email, firstName, lastName, password, externalId, userId, username);
        bootstrap.onApplicationEvent(new ExternalGroupAuthorizationEvent(user, false, getAuthorities(externalAuthorities),add));

        users = db.query("userName eq \""+username +"\" and origin eq \""+origin+"\"", IdentityZoneHolder.get().getId());
        assertEquals(1, users.size());
        ScimUser created = users.get(0);
        validateAuthoritiesCreated(add?externalAuthorities:new String[0], userAuthorities, origin, created);

        externalAuthorities = new String[] {"extTest1","extTest2"};
        bootstrap.onApplicationEvent(new ExternalGroupAuthorizationEvent(user, false, getAuthorities(externalAuthorities),add));
        validateAuthoritiesCreated(add?externalAuthorities:new String[0], userAuthorities, origin, created);
    }

    @Test
    public void doNotAddNonExistentUsers() throws Exception {
        nonExistentGroupThroughEvent(false);
    }

    protected void validateAuthoritiesCreated(String[] externalAuthorities, String[] userAuthorities, String origin, ScimUser created) {
        Set<ScimGroup> groups = mdb.getGroupsWithMember(created.getId(), true, IdentityZoneHolder.get().getId());
        String[] expected = merge(externalAuthorities,userAuthorities);
        String[] actual = getGroupNames(groups);
        assertThat(actual, IsArrayContainingInAnyOrder.arrayContainingInAnyOrder(expected));

        List<String> external = Arrays.asList(externalAuthorities);
        for (ScimGroup g : groups) {
            ScimGroupMember m = mdb.getMemberById(g.getId(), created.getId(), IdentityZoneHolder.get().getId());
            if (external.contains(g.getDisplayName())) {
                assertEquals("Expecting relationship for Group[" + g.getDisplayName() + "] be of different origin.", origin, m.getOrigin());
            } else {
                assertEquals("Expecting relationship for Group[" + g.getDisplayName() + "] be of different origin.", OriginKeys.UAA, m.getOrigin());
            }
        }
    }

    @Test
    public void canUpdateEmailThroughEvent() throws Exception {
        String[] externalAuthorities = new String[] {"extTest1","extTest2","extTest3"};
        String[] userAuthorities = new String[] {"usrTest1","usrTest2","usrTest3"};
        String origin = "testOrigin";
        addIdentityProvider(jdbcTemplate,origin);
        String email = "test@test.org";
        String newEmail = "test@test2.org";
        String firstName = "FirstName";
        String lastName = "LastName";
        String password = "testPassword";
        String externalId = null;
        String userId = new RandomValueStringGenerator().generate();
        String username = new RandomValueStringGenerator().generate();
        UaaUser user = getUaaUser(userAuthorities, origin, email, firstName, lastName, password, externalId, userId, username);
        ScimUserBootstrap bootstrap = new ScimUserBootstrap(db, gdb, mdb, Arrays.asList(user));
        bootstrap.afterPropertiesSet();

        List<ScimUser> users = db.query("userName eq \""+username +"\" and origin eq \""+origin+"\"", IdentityZoneHolder.get().getId());
        assertEquals(1, users.size());
        userId = users.get(0).getId();
        user = getUaaUser(userAuthorities, origin, newEmail, firstName, lastName, password, externalId, userId, username);

        bootstrap.onApplicationEvent(new ExternalGroupAuthorizationEvent(user, true, getAuthorities(externalAuthorities),true));
        users = db.query("userName eq \""+username +"\" and origin eq \""+origin+"\"", IdentityZoneHolder.get().getId());
        assertEquals(1, users.size());
        ScimUser created = users.get(0);
        validateAuthoritiesCreated(externalAuthorities, userAuthorities, origin, created);
        assertEquals(newEmail, created.getPrimaryEmail());

        user = user.modifyEmail("test123@test.org");
        //Ensure email doesn't get updated if event instructs not to update.
        bootstrap.onApplicationEvent(new ExternalGroupAuthorizationEvent(user, false, getAuthorities(externalAuthorities),true));
        users = db.query("userName eq \""+username +"\" and origin eq \""+origin+"\"", IdentityZoneHolder.get().getId());
        assertEquals(1, users.size());
        created = users.get(0);
        validateAuthoritiesCreated(externalAuthorities, userAuthorities, origin, created);
        assertEquals(newEmail, created.getPrimaryEmail());

        bootstrap.onApplicationEvent(new ExternalGroupAuthorizationEvent(user, true, getAuthorities(externalAuthorities),true));
        users = db.query("userName eq \""+username +"\" and origin eq \""+origin+"\"", IdentityZoneHolder.get().getId());
        assertEquals(1, users.size());
        created = users.get(0);
        validateAuthoritiesCreated(externalAuthorities, userAuthorities, origin, created);
        assertEquals("test123@test.org", created.getPrimaryEmail());
    }


    private UaaUser getUaaUser(String[] userAuthorities, String origin, String email, String firstName, String lastName, String password, String externalId, String userId, String username) {
        return new UaaUser(
            userId,
            username,
            password,
            email,
            getAuthorities(userAuthorities),
            firstName,
            lastName,
            new Date(),
            new Date(),
            origin,
            externalId,
            false,
            IdentityZoneHolder.get().getId(),
            userId,
            new Date()
        );
    }

    @Test
    public void addUsersWithSameUsername() throws Exception {
        String origin = "testOrigin";
        addIdentityProvider(jdbcTemplate,origin);
        String email = "test@test.org";
        String firstName = "FirstName";
        String lastName = "LastName";
        String password = "testPassword";
        String externalId = null;
        String userId = new RandomValueStringGenerator().generate();
        String username = new RandomValueStringGenerator().generate();
        UaaUser user = getUaaUser(new String[0], origin, email, firstName, lastName, password, externalId, userId, username);
        ScimUserBootstrap bootstrap = new ScimUserBootstrap(db, gdb, mdb, Arrays.asList(user));
        bootstrap.afterPropertiesSet();

        addIdentityProvider(jdbcTemplate,"newOrigin");
        bootstrap = new ScimUserBootstrap(db, gdb, mdb, Arrays.asList(user, user.modifySource("newOrigin", "")));
        bootstrap.afterPropertiesSet();
        assertEquals(2, db.retrieveAll(IdentityZoneHolder.get().getId()).size());
    }

    @Test
    public void testConcurrentAuthEventsRaceCondition() throws Exception {
        int numthreads = 5;
        int numgroups = 100;

        String[] externalAuthorities = new String[] {"extTest1","extTest2","extTest3"};
        String[] userAuthorities = new String[] {"usrTest1","usrTest2","usrTest3"};
        String origin = "testOrigin";
        addIdentityProvider(jdbcTemplate,origin);
        String email = "test@test.org";
        String firstName = "FirstName";
        String lastName = "LastName";
        String password = "testPassword";
        String externalId = null;
        String userId = new RandomValueStringGenerator().generate();
        String username = new RandomValueStringGenerator().generate();
        UaaUser user = getUaaUser(userAuthorities, origin, email, firstName, lastName, password, externalId, userId, username);
        ScimUserBootstrap bootstrap = new ScimUserBootstrap(db, gdb, mdb, Arrays.asList(user));
        bootstrap.afterPropertiesSet();

        List<ScimUser> scimUsers = db.query("userName eq \""+username +"\" and origin eq \""+origin+"\"", IdentityZoneHolder.get().getId());
        assertEquals(1, scimUsers.size());
        ScimUser scimUser = scimUsers.get(0);
        ScimGroupMember member = new ScimGroupMember<>(scimUser);
        user = getUaaUser(userAuthorities, origin, email, firstName, lastName, password, externalId, member.getMemberId(), username);
        for (int i = 0; i < numgroups; i++) {
            gdb.create(new ScimGroup("group" + i, "group" + i, IdentityZoneHolder.get().getId()), IdentityZoneHolder.get().getId());
            String gid = gdb.query("displayName eq \"group"+i+"\"", IdentityZoneHolder.get().getId()).get(0).getId();
            mdb.addMember(gid, member, IdentityZoneHolder.get().getId());
        }

        bootstrap.onApplicationEvent(new ExternalGroupAuthorizationEvent(user, true, getAuthorities(externalAuthorities), true));

        ExternalGroupAuthorizationEvent externalGroupAuthorizationEvent = new ExternalGroupAuthorizationEvent(user, false, getAuthorities(externalAuthorities), true);

        Thread[] threads = new Thread[numthreads];
        for (int i = 0; i < numthreads; i++) {
            threads[i] = new Thread(new AuthEventRunnable(externalGroupAuthorizationEvent, bootstrap));
            threads[i].start();
        }
        for (int i = 0; i < numthreads; i++) {
            threads[i].join();
        }
        if (AuthEventRunnable.failure != null) {
            throw AuthEventRunnable.failure;
        }
    }

    private static class AuthEventRunnable implements Runnable {

        public static volatile AssertionError failure = null;
        private final int iterations = 50;

        private final ExternalGroupAuthorizationEvent externalGroupAuthorizationEvent;
        private final ScimUserBootstrap bootstrap;

        public AuthEventRunnable(ExternalGroupAuthorizationEvent externalGroupAuthorizationEvent, ScimUserBootstrap bootstrap) {
			this.externalGroupAuthorizationEvent = externalGroupAuthorizationEvent;
			this.bootstrap = bootstrap;
		}

		@Override
		public void run() {
			for (int i = 0; i < iterations; i++) {
				if (failure != null) break;
				try {
					bootstrap.onApplicationEvent(externalGroupAuthorizationEvent);
				} catch (MemberNotFoundException e) {
					if (failure == null) {
						failure = new AssertionError("MemberNotFoundException in Test thread", e);
						break;
					}
				} catch (Exception e) {
					failure = new AssertionError("Exception in Test thread", e);
				}
			}
		}
    }

    private List<GrantedAuthority> getAuthorities(String[] auth) {
        ArrayList<GrantedAuthority> result = new ArrayList<>();
        for (String s : auth) {
            result.add(new SimpleGrantedAuthority(s));
        }
        return result;
    }

    private String[] merge(String[] a, String[] b) {
        String[] result = new String[a.length+b.length];
        System.arraycopy(a,0,result,0,a.length);
        System.arraycopy(b,0,result,a.length,b.length);
        return result;
    }

    private String[] getGroupNames(Set<ScimGroup> groups) {
        String[] result = new String[groups!=null?groups.size():0];
        if (result.length==0) {
            return result;
        }
        int index = 0;
        for (ScimGroup group : groups) {
            result[index++] = group.getDisplayName();
        }
        return result;
    }

}