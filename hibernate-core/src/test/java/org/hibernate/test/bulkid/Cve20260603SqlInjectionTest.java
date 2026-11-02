/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.test.bulkid;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Inheritance;
import javax.persistence.InheritanceType;
import javax.persistence.PrimaryKeyJoinColumn;
import javax.persistence.Table;

import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.hibernate.hql.spi.id.inline.InlineIdsOrClauseBulkIdStrategy;

import org.hibernate.testing.junit4.BaseCoreFunctionalTestCase;
import org.junit.Test;

import static org.hibernate.testing.transaction.TransactionUtil.doInHibernate;
import static org.junit.Assert.assertEquals;

/**
 * Regression test for CVE-2026-0603 (EQSTLab PoC:
 * https://github.com/EQSTLab/CVE-2026-0603). A bulk DELETE bound to a
 * quote-containing string PK must affect only the matching row.
 */
public class Cve20260603SqlInjectionTest extends BaseCoreFunctionalTestCase {

	private static final String MALICIOUS_USERNAME = "x' or '1'='1";

	@Override
	protected Class<?>[] getAnnotatedClasses() {
		return new Class<?>[] { User.class, UserProfile.class };
	}

	@Override
	protected Configuration constructConfiguration() {
		Configuration configuration = super.constructConfiguration();
		configuration.setProperty(
				AvailableSettings.HQL_BULK_ID_STRATEGY,
				InlineIdsOrClauseBulkIdStrategy.class.getName()
		);
		return configuration;
	}

	@Override
	protected boolean isCleanupTestDataRequired() {
		return true;
	}

	@Test
	public void bulkDeleteWithMaliciousStringPkAffectsOnlyMatchingRow() {
		doInHibernate( this::sessionFactory, session -> {
			session.persist( new UserProfile( "alice", "USER", "alice@example.com", "ENG", "" ) );
			session.persist( new UserProfile( "bob",   "USER", "bob@example.com",   "ENG", "" ) );
			session.persist( new UserProfile( MALICIOUS_USERNAME, "USER", "evil@example.com", "ENG", "" ) );
		} );

		doInHibernate( this::sessionFactory, session -> {
			session.createQuery( "delete from UserProfile u where u.username = :username" )
					.setParameter( "username", MALICIOUS_USERNAME )
					.executeUpdate();
		} );

		final int remaining = doInHibernate( this::sessionFactory, session -> {
			return ( (Number) session.createQuery( "select count(u) from UserProfile u" ).uniqueResult() ).intValue();
		} );

		assertEquals(
				"CVE-2026-0603: bulk DELETE bound to a quote-containing string PK "
						+ "must affect exactly the matching row, leaving 2 of 3 rows intact. "
						+ "If fewer survive, the InlineIdsOrClauseBuilder inlined the PK "
						+ "without escaping and the EQSTLab PoC reproduces against this build.",
				2, remaining
		);
	}

	@Entity(name = "User")
	@Table(name = "users_base")
	@Inheritance(strategy = InheritanceType.JOINED)
	public static class User {

		@Id
		@Column(name = "username", nullable = false, length = 255)
		private String username;

		@Column(name = "role", nullable = false, length = 50)
		private String role;

		@Column(name = "email", nullable = false, length = 255)
		private String email;

		public User() {
		}

		public User(String username, String role, String email) {
			this.username = username;
			this.role = role;
			this.email = email;
		}

		public String getUsername() {
			return username;
		}

		public String getRole() {
			return role;
		}

		public String getEmail() {
			return email;
		}
	}

	@Entity(name = "UserProfile")
	@Table(name = "users_profile")
	@PrimaryKeyJoinColumn(name = "username")
	public static class UserProfile extends User {

		@Column(name = "department", nullable = false, length = 100)
		private String department;

		@Column(name = "bio", length = 255)
		private String bio;

		public UserProfile() {
		}

		public UserProfile(String username, String role, String email, String department, String bio) {
			super( username, role, email );
			this.department = department;
			this.bio = bio;
		}

		public String getDepartment() {
			return department;
		}

		public String getBio() {
			return bio;
		}
	}
}
