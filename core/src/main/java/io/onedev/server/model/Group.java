package io.onedev.server.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.OneToMany;

import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import org.hibernate.validator.constraints.NotEmpty;

import io.onedev.server.util.facade.GroupFacade;
import io.onedev.server.web.editable.annotation.Editable;

@Entity
@Cache(usage=CacheConcurrencyStrategy.READ_WRITE)
@Editable
public class Group extends AbstractEntity {

	private static final long serialVersionUID = 1L;

	@Column(unique=true, nullable=false)
	private String name;
	
	private String description;
	
	private boolean administrator;
	
	private boolean canCreateProjects = true;

	@OneToMany(mappedBy="group", cascade=CascadeType.REMOVE)
	private Collection<GroupAuthorization> authorizations = new ArrayList<>();
	
	@OneToMany(mappedBy="group", cascade=CascadeType.REMOVE)
	private Collection<Membership> memberships = new ArrayList<>();
	
	private transient Collection<User> members;
	
	@Editable(order=100)
	@NotEmpty
	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	@Editable(order=200, description="Optionally describe the group")
	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	@Editable(order=300)
	public boolean isAdministrator() {
		return administrator;
	}

	public void setAdministrator(boolean administrator) {
		this.administrator = administrator;
	}

	@Editable(order=400)
	public boolean isCanCreateProjects() {
		return canCreateProjects;
	}

	public void setCanCreateProjects(boolean canCreateProjects) {
		this.canCreateProjects = canCreateProjects;
	}

	public Collection<GroupAuthorization> getAuthorizations() {
		return authorizations;
	}

	public void setAuthorizations(Collection<GroupAuthorization> authorizations) {
		this.authorizations = authorizations;
	}

	public Collection<Membership> getMemberships() {
		return memberships;
	}

	public void setMemberships(Collection<Membership> memberships) {
		this.memberships = memberships;
	}

	public Collection<User> getMembers() {
		if (members == null) {
			members = new HashSet<>();
			for (Membership membership: getMemberships()) {
				members.add(membership.getUser());
			}
		}
		return members;
	}

	@Override
	public int compareTo(AbstractEntity entity) {
		Group group = (Group) entity;
		return getName().compareTo(group.getName());
	}

	public GroupFacade getFacade() {
		return new GroupFacade(this);
	}
	
}
