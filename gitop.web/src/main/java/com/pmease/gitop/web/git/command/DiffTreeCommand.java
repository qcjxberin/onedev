package com.pmease.gitop.web.git.command;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;

import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.io.ByteStreams;
import com.pmease.commons.util.execution.Commandline;
import com.pmease.gitop.web.page.project.source.commit.diff.patch.Patch;

public class DiffTreeCommand extends AbstractDiffCommand<Patch, DiffTreeCommand> {

	boolean recurse = true;
	private boolean root = true;
	
	public DiffTreeCommand(File repoDir) {
		super(repoDir);
	}

	@Override
	protected Commandline newCommand() {
		Commandline cmd = super.newCommand();
		
		if (root) {
			cmd.addArgs("--root");
		}
		
		if (recurse) {
			cmd.addArgs("-r");
		}
		
		cmd.addArgs("--no-commit-id");
		
		return cmd;
	}
	
	@Override
	protected void addArgPaths(Commandline cmd) {
		cmd.addArgs("--");
		for (String path : paths) {
			if (!Strings.isNullOrEmpty(path)) {
				cmd.addArgs(path);
			}
		}
	}
	
	public DiffTreeCommand recurse(boolean recurse) {
		this.recurse = recurse;
		return self();
	}
	
	public DiffTreeCommand root(boolean root) {
		this.root = root;
		return self();
	}
	
	static final int BUFFER_SIZE = 1024 * 8;
	
	@Override
	public Patch call() {
		Commandline cmd = buildCommand();
		
		final Patch patch = new Patch();
		
		try (ByteArrayOutputStream out = new ByteArrayOutputStream(BUFFER_SIZE)) {
			
			cmd.execute(out, errorLogger).checkReturnCode();
			try (ByteArrayInputStream in = ByteStreams.newInputStreamSupplier(
					out.toByteArray()).getInput()) {
				patch.parse(in);
				return patch;
			}
		} catch (IOException e) {
			throw Throwables.propagate(e);
		}
	}

	@Override
	protected String getSubCommand() {
		return "diff-tree";
	}

	@Override
	protected DiffTreeCommand self() {
		return this;
	}
}