package io.onedev.server.web.page.project.blob;

import static org.apache.wicket.ajax.attributes.CallbackParameter.explicit;

import java.io.IOException;
import java.io.Serializable;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.apache.commons.lang3.SerializationUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URIBuilder;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.apache.wicket.Component;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.behavior.AttributeAppender;
import org.apache.wicket.core.request.handler.IPartialPageRequestHandler;
import org.apache.wicket.event.IEvent;
import org.apache.wicket.markup.ComponentTag;
import org.apache.wicket.markup.head.IHeaderResponse;
import org.apache.wicket.markup.head.JavaScriptHeaderItem;
import org.apache.wicket.markup.head.OnDomReadyHeaderItem;
import org.apache.wicket.markup.head.OnLoadHeaderItem;
import org.apache.wicket.markup.html.WebMarkupContainer;
import org.apache.wicket.markup.html.form.upload.FileUpload;
import org.apache.wicket.model.AbstractReadOnlyModel;
import org.apache.wicket.request.IRequestParameters;
import org.apache.wicket.request.cycle.RequestCycle;
import org.apache.wicket.request.handler.resource.ResourceReferenceRequestHandler;
import org.apache.wicket.request.mapper.parameter.PageParameters;
import org.apache.wicket.request.resource.PackageResourceReference;
import org.apache.wicket.util.visit.IVisit;
import org.apache.wicket.util.visit.IVisitor;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.Sets;

import io.onedev.jsymbol.TokenPosition;
import io.onedev.jsymbol.util.NoAntiCacheImage;
import io.onedev.launcher.loader.ListenerRegistry;
import io.onedev.server.OneDev;
import io.onedev.server.event.RefUpdated;
import io.onedev.server.git.BlobContent;
import io.onedev.server.git.BlobEdits;
import io.onedev.server.git.BlobIdent;
import io.onedev.server.git.GitUtils;
import io.onedev.server.git.exception.NotTreeException;
import io.onedev.server.git.exception.ObjectAlreadyExistsException;
import io.onedev.server.git.exception.ObsoleteCommitException;
import io.onedev.server.manager.CodeCommentManager;
import io.onedev.server.manager.ProjectManager;
import io.onedev.server.manager.PullRequestManager;
import io.onedev.server.manager.UserManager;
import io.onedev.server.model.CodeComment;
import io.onedev.server.model.Project;
import io.onedev.server.model.PullRequest;
import io.onedev.server.model.User;
import io.onedev.server.model.support.TextRange;
import io.onedev.server.persistence.UnitOfWork;
import io.onedev.server.search.code.CommitIndexed;
import io.onedev.server.search.code.IndexManager;
import io.onedev.server.search.code.SearchManager;
import io.onedev.server.search.code.hit.QueryHit;
import io.onedev.server.search.code.query.BlobQuery;
import io.onedev.server.search.code.query.TextQuery;
import io.onedev.server.security.SecurityUtils;
import io.onedev.server.web.PrioritizedComponentRenderer;
import io.onedev.server.web.behavior.AbstractPostAjaxBehavior;
import io.onedev.server.web.component.floating.FloatingPanel;
import io.onedev.server.web.component.link.ArchiveMenuLink;
import io.onedev.server.web.component.link.ViewStateAwareAjaxLink;
import io.onedev.server.web.component.link.ViewStateAwarePageLink;
import io.onedev.server.web.component.menu.MenuItem;
import io.onedev.server.web.component.menu.MenuLink;
import io.onedev.server.web.component.modal.ModalLink;
import io.onedev.server.web.component.modal.ModalPanel;
import io.onedev.server.web.component.revisionpicker.RevisionPicker;
import io.onedev.server.web.page.project.ProjectPage;
import io.onedev.server.web.page.project.blob.navigator.BlobNavigator;
import io.onedev.server.web.page.project.blob.render.BlobRenderContext;
import io.onedev.server.web.page.project.blob.render.BlobRendererContribution;
import io.onedev.server.web.page.project.blob.render.BlobUploadException;
import io.onedev.server.web.page.project.blob.render.view.Markable;
import io.onedev.server.web.page.project.blob.search.SearchMenuContributor;
import io.onedev.server.web.page.project.blob.search.advanced.AdvancedSearchPanel;
import io.onedev.server.web.page.project.blob.search.quick.QuickSearchPanel;
import io.onedev.server.web.page.project.blob.search.result.SearchResultPanel;
import io.onedev.server.web.page.project.commits.ProjectCommitsPage;
import io.onedev.server.web.util.resource.RawBlobResourceReference;
import io.onedev.server.web.websocket.PageDataChanged;
import io.onedev.server.web.websocket.WebSocketManager;

@SuppressWarnings("serial")
public class ProjectBlobPage extends ProjectPage implements BlobRenderContext {

	private static final String PARAM_REVISION = "revision";
	
	private static final String PARAM_PATH = "path";
	
	private static final String PARAM_INITIAL_NEW_PATH = "initial-new-path";
	
	private static final String PARAM_REQUEST = "request";
	
	private static final String PARAM_COMMENT = "comment";
	
	private static final String PARAM_MODE = "mode";
	
	private static final String PARAM_QUERY = "query";
	
	private static final String PARAM_MARK = "mark";
	
	private static final String PARAM_RAW = "raw";
	
	private static final String REVISION_PICKER_ID = "revisionPicker";
	
	private static final String BLOB_NAVIGATOR_ID = "blobNavigator";

	private static final String BLOB_CONTENT_ID = "blobContent";
	
	private State state = new State();
	
	private ObjectId resolvedRevision;
	
	private Component revisionIndexing;
	
	private WebMarkupContainer searchResult;
	
	private AbstractPostAjaxBehavior ajaxBehavior;
	
	public ProjectBlobPage(PageParameters params) {
		super(params);
		
		List<String> revisionAndPathSegments = new ArrayList<>();
		String segment = params.get(PARAM_REVISION).toString();
		if (segment != null && segment.length() != 0)
			revisionAndPathSegments.add(segment);
		segment = params.get(PARAM_PATH).toString();
		if (segment != null && segment.length() != 0)
			revisionAndPathSegments.add(segment);
		
		for (int i=0; i<params.getIndexedCount(); i++) {
			segment = params.get(i).toString();
			if (segment.length() != 0)
				revisionAndPathSegments.add(segment);
		}

		BlobIdent blobIdent = new BlobIdent(getProject(), revisionAndPathSegments); 
		state = new State(blobIdent);

		String modeStr = params.get(PARAM_MODE).toString();
		if (modeStr != null)
			state.mode = Mode.valueOf(modeStr.toUpperCase());

		resolvedRevision = getProject().getObjectId(state.blobIdent.revision);
		
		state.mark = TextRange.of(params.get(PARAM_MARK).toString());
		
		state.requestId = params.get(PARAM_REQUEST).toOptionalLong();
		
		state.commentId = params.get(PARAM_COMMENT).toOptionalLong();
		
		state.query = params.get(PARAM_QUERY).toString();
	
		state.initialNewPath = params.get(PARAM_INITIAL_NEW_PATH).toString();
		
		if (state.mode == Mode.ADD || state.mode == Mode.EDIT || state.mode == Mode.DELETE) {
			if (!isOnBranch()) 
				throw new IllegalArgumentException("Files can only be edited on branch");
			
			String path = state.blobIdent.path;
			if (path != null && state.blobIdent.isTree())
				path += "/";
			if (!SecurityUtils.canModify(getProject(), state.blobIdent.revision, path))
				unauthorized();
		}

		Boolean raw = params.get(PARAM_RAW).toOptionalBoolean();
		if (raw != null && raw) {
			RequestCycle.get().scheduleRequestHandlerAfterCurrent(
					new ResourceReferenceRequestHandler(new RawBlobResourceReference(), getPageParameters()));
		}
	}
	
	@Override
	protected void onInitialize() {
		super.onInitialize();

		newRevisionPicker(null);
		newBlobNavigator(null);
		newBlobOperations(null);
		
		add(revisionIndexing = new WebMarkupContainer("revisionIndexing") {

			@Override
			protected void onInitialize() {
				super.onInitialize();
				add(new NoAntiCacheImage("icon", new PackageResourceReference(ProjectBlobPage.class, "indexing.gif")));
				setOutputMarkupPlaceholderTag(true);
			}

			@Override
			protected void onConfigure() {
				super.onConfigure();

				RevCommit commit = getProject().getRevCommit(resolvedRevision);
				IndexManager indexManager = OneDev.getInstance(IndexManager.class);
				if (!indexManager.isIndexed(getProject(), commit)) {
					OneDev.getInstance(IndexManager.class).indexAsync(getProject(), commit);
					setVisible(true);
				} else {
					setVisible(false);
				}
			}
			
		});

		newBlobContent(null);

		add(searchResult = new WebMarkupContainer("searchResult"));

		List<QueryHit> queryHits;
		if (state.query != null) {
			BlobQuery query = new TextQuery.Builder()
					.term(state.query)
					.wholeWord(true)
					.caseSensitive(true) 
					.count(SearchResultPanel.MAX_QUERY_ENTRIES)
					.build();
			try {
				SearchManager searchManager = OneDev.getInstance(SearchManager.class);
				queryHits = searchManager.search(projectModel.getObject(), getProject().getRevCommit(resolvedRevision), 
						query);
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			}								
		} else {
			queryHits = null;
		}
		
		newSearchResult(null, queryHits);

		add(ajaxBehavior = new AbstractPostAjaxBehavior() {
			
			@Override
			protected void respond(AjaxRequestTarget target) {
				IRequestParameters params = RequestCycle.get().getRequest().getPostParameters(); 
				String action = params.getParameterValue("action").toString();
				switch (action) {
				case "quickSearch": 
					new ModalPanel(target) {
						
						@Override
						protected Component newContent(String id) {
							return newQuickSearchPanel(id, this);
						}
						
					};
					break;
				case "advancedSearch":
					new ModalPanel(target) {
						
						@Override
						protected Component newContent(String id) {
							return newAdvancedSearchPanel(id, this);
						}
						
					};
					break;
				default:
					throw new IllegalStateException("Unexpected action: " + action);
				}
			}
			
		});
	}

	/*
	 * In case we are on a branch, this operation makes sure that the branch resolves
	 * to a certain commit during the life cycle of our page, unless the page is 
	 * refreshed. This can avoid the issue that displayed file content and subsequent 
	 * operations encounters different commit if someone commits to the branch while 
	 * we are staying on the page. 
	 */
	@Override
	protected Map<String, ObjectId> getObjectIdCache() {
		Map<String, ObjectId> objectIdCache = new HashMap<>();
		if (resolvedRevision != null)
			objectIdCache.put(state.blobIdent.revision, resolvedRevision);
		return objectIdCache;
	}

	@Override
	public CodeComment getOpenComment() {
		if (state.commentId != null)
			return OneDev.getInstance(CodeCommentManager.class).load(state.commentId);
		else
			return null;
	}
	
	@Override
	public PullRequest getPullRequest() {
		if (state.requestId != null)
			return OneDev.getInstance(PullRequestManager.class).load(state.requestId);
		else
			return null;
	}
	
	private void newBlobNavigator(@Nullable AjaxRequestTarget target) {
		Component blobNavigator = new BlobNavigator(BLOB_NAVIGATOR_ID, this);
		if (target != null) {
			replace(blobNavigator);
			target.add(blobNavigator);
		} else {
			add(blobNavigator);
		}
	}

	private void newBlobOperations(@Nullable AjaxRequestTarget target) {
		WebMarkupContainer blobOperations = new WebMarkupContainer("blobOperations");
		blobOperations.setOutputMarkupId(true);
		
		blobOperations.add(new MenuLink("add") {

			@Override
			protected List<MenuItem> getMenuItems(FloatingPanel dropdown) {
				List<MenuItem> menuItems = new ArrayList<>();
				menuItems.add(new MenuItem() {

					@Override
					public String getLabel() {
						return "Create New File";
					}

					@Override
					public WebMarkupContainer newLink(String id) {
						return new ViewStateAwareAjaxLink<Void>(id) {

							@Override
							public void onClick(AjaxRequestTarget target) {
								onModeChange(target, Mode.ADD);
								dropdown.close();
							}
							
						};
					}
					
				});
				
				menuItems.add(new MenuItem() {

					@Override
					public String getLabel() {
						return "Upload Files";
					}

					@Override
					public WebMarkupContainer newLink(String id) {
						return new ModalLink(id) {

							@Override
							public void onClick(AjaxRequestTarget target) {
								super.onClick(target);
								dropdown.close();
							}

							@Override
							protected Component newContent(String id, ModalPanel modal) {
								return new BlobUploadPanel(id, ProjectBlobPage.this) {

									@Override
									void onCancel(AjaxRequestTarget target) {
										modal.close();
									}

									@Override
									void onCommitted(AjaxRequestTarget target, RefUpdated refUpdated) {
										ProjectBlobPage.this.onCommitted(target, refUpdated);
										modal.close();
									}
									
								};
							}
							
						};
					}
					
				});
				return menuItems;
			}
			
			@Override
			protected void disableLink(ComponentTag tag) {
				super.disableLink(tag);
				tag.append("class", "disabled", " ");
				tag.put("title", "Add files not allowed. Submit pull request for review instead");
			}

			@Override
			protected void onConfigure() {
				super.onConfigure();
				
				Project project = getProject();
				if ((state.mode == Mode.VIEW || state.mode == Mode.BLAME) 
						&& isOnBranch() && state.blobIdent.isTree() 
						&& SecurityUtils.canWriteCode(project.getFacade())) {
					ProjectManager projectManager = OneDev.getInstance(ProjectManager.class);
					setEnabled(!projectManager.isModificationNeedsQualityCheck(
							getLoginUser(), project, state.blobIdent.revision, null));
				} else {
					setVisible(false);
				}
			}
			
		});
		
		blobOperations.add(new MenuLink("search") {

			@Override
			protected List<MenuItem> getMenuItems(FloatingPanel dropdown) {
				List<MenuItem> menuItems = new ArrayList<>();
				menuItems.add(new MenuItem() {

					@Override
					public String getShortcut() {
						return "T";
					}

					@Override
					public String getLabel() {
						return "Quick Search";
					}

					@Override
					public WebMarkupContainer newLink(String id) {
						return new ModalLink(id) {
							
							@Override
							public void onClick(AjaxRequestTarget target) {
								super.onClick(target);
								dropdown.close();
							}

							@Override
							protected Component newContent(String id, ModalPanel modal) {
								return newQuickSearchPanel(id, modal);
							}
							
						};
					}
					
				});
				menuItems.add(new MenuItem() {

					@Override
					public String getShortcut() {
						return "V";
					}

					@Override
					public String getLabel() {
						return "Advanced Search";
					}

					@Override
					public WebMarkupContainer newLink(String id) {
						return new ModalLink(id) {
							
							@Override
							public void onClick(AjaxRequestTarget target) {
								super.onClick(target);
								dropdown.close();
							}

							@Override
							protected Component newContent(String id, ModalPanel modal) {
								return newAdvancedSearchPanel(id, modal);
							}
							
						};
					}
					
				});
				
				IVisitor<Component, Component> visitor = new IVisitor<Component, Component>() {

					@Override
					public void component(Component object, IVisit<Component> visit) {
						visit.stop(object);
					}
					
				};
				SearchMenuContributor contributor = (SearchMenuContributor) getPage().visitChildren(
						SearchMenuContributor.class, visitor);
				if (contributor != null)
					menuItems.addAll(contributor.getMenuItems(dropdown));
				
				return menuItems;
			}
			
		});
		
		ProjectCommitsPage.State commitsState = new ProjectCommitsPage.State();
		commitsState.compareWith = resolvedRevision.name();
		if (state.blobIdent.path != null)
			commitsState.query = String.format("path(%s)", ProjectBlobPage.this.state.blobIdent.path);
		blobOperations.add(new ViewStateAwarePageLink<Void>("history", ProjectCommitsPage.class, 
				ProjectCommitsPage.paramsOf(getProject(), commitsState)));
		
		blobOperations.add(new ArchiveMenuLink("download", projectModel) {

			@Override
			protected String getRevision() {
				return state.blobIdent.revision;
			}

			@Override
			protected void onConfigure() {
				super.onConfigure();
				setVisible(state.blobIdent.path == null);
			}

		});
		
		if (target != null) {
			replace(blobOperations);
			target.add(blobOperations);
		} else {
			add(blobOperations);
		}
	}

	private QuickSearchPanel newQuickSearchPanel(String id, ModalPanel modal) {
		return new QuickSearchPanel(id, projectModel, new AbstractReadOnlyModel<String>() {

			@Override
			public String getObject() {
				return state.blobIdent.revision;
			}
			
		}) {

			@Override
			protected void onSelect(AjaxRequestTarget target, QueryHit hit) {
				BlobIdent selected = new BlobIdent(state.blobIdent.revision, hit.getBlobPath(), 
						FileMode.REGULAR_FILE.getBits()); 
				ProjectBlobPage.this.onSelect(target, selected, hit.getTokenPos());
				modal.close();
			}
			
			@Override
			protected void onMoreQueried(AjaxRequestTarget target, List<QueryHit> hits) {
				newSearchResult(target, hits);
				resizeWindow(target);
				modal.close();
			}

			@Override
			protected void onCancel(AjaxRequestTarget target) {
				modal.close();
			}
			
		};
	}
	
	private AdvancedSearchPanel advancedSearchPanel;
	
	private ModalPanel advancedSearchPanelModal;
	
	private AdvancedSearchPanel newAdvancedSearchPanel(String id, ModalPanel modal) {
		/*
		 * Re-use advanced search panel instance so that search options can be preserved in the page
		 */
		advancedSearchPanelModal = modal;
		if (advancedSearchPanel == null) {
			advancedSearchPanel = new AdvancedSearchPanel(id, projectModel, new AbstractReadOnlyModel<String>() {
	
				@Override
				public String getObject() {
					return state.blobIdent.revision;
				}
				
			}) {
	
				@Override
				protected void onSearchComplete(AjaxRequestTarget target, List<QueryHit> hits) {
					newSearchResult(target, hits);
					resizeWindow(target);
					advancedSearchPanelModal.close();
				}
	
				@Override
				protected void onCancel(AjaxRequestTarget target) {
					advancedSearchPanelModal.close();
				}
	
				@Override
				protected BlobIdent getCurrentBlob() {
					return state.blobIdent;
				}
				
			};
		} 
		return advancedSearchPanel;
	}
	
	private void newBlobContent(@Nullable AjaxRequestTarget target) {
		PrioritizedComponentRenderer mostPrioritizedRenderer = null;
		for (BlobRendererContribution contribution: OneDev.getExtensions(BlobRendererContribution.class)) {
			PrioritizedComponentRenderer renderer = contribution.getRenderer(this);
			if (renderer != null) {
				if (mostPrioritizedRenderer == null || mostPrioritizedRenderer.getPriority() > renderer.getPriority())
					mostPrioritizedRenderer = renderer;
			}
		}
		Component blobContent = Preconditions.checkNotNull(mostPrioritizedRenderer).render(BLOB_CONTENT_ID);
		if (target != null) {
			replace(blobContent);
			target.add(blobContent);
		} else {
			add(blobContent);
		}
	}
	
	private void pushState(AjaxRequestTarget target) {
		PageParameters params = paramsOf(getProject(), state);
		CharSequence url = RequestCycle.get().urlFor(ProjectBlobPage.class, params);
		pushState(target, url.toString(), state);
	}
	
	private void newRevisionPicker(@Nullable AjaxRequestTarget target) {
		Component revisionPicker = new RevisionPicker(REVISION_PICKER_ID, projectModel, state.blobIdent.revision, true) {

			@Override
			protected String getRevisionUrl(String revision) {
				BlobIdent blobIdent = new BlobIdent(revision, null, FileMode.TREE.getBits());
				State state = new State(blobIdent);
				PageParameters params = ProjectBlobPage.paramsOf(projectModel.getObject(), state);
				return urlFor(ProjectBlobPage.class, params).toString();
			}

			@Override
			protected void onSelect(AjaxRequestTarget target, String revision) {
				BlobIdent newBlobIdent = new BlobIdent(state.blobIdent);
				newBlobIdent.revision = revision;
				if (newBlobIdent.path != null) {
					try (RevWalk revWalk = new RevWalk(getProject().getRepository())) {
						RevTree revTree = getProject().getRevCommit(revision, true).getTree();
						TreeWalk treeWalk = TreeWalk.forPath(getProject().getRepository(), newBlobIdent.path, revTree);
						if (treeWalk != null) {
							newBlobIdent.mode = treeWalk.getRawMode(0);
						} else {
							newBlobIdent.path = null;
							newBlobIdent.mode = FileMode.TREE.getBits();
						}
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
				}
				
				ProjectBlobPage.this.onSelect(target, newBlobIdent, null);
			}
			
		}; 
		if (target != null) {
			replace(revisionPicker);
			target.add(revisionPicker);
		} else {
			add(revisionPicker);
		}
	}
	
	/*
	 * This method represents changing of resolved revision, instead of the named revision. 
	 * It is possible that resolved revision changes while named revision remains unchanged. 
	 * For instance when a file on a branch is edited from this page, the named revision 
	 * remains unchanged (still point to the branch), but the underlying resolved revision 
	 * (the commit) has been changed 
	 *   
	 * @param target
	 */
	private void onResolvedRevisionChange(AjaxRequestTarget target) {
		/* 
		 * A hack to reset resolved revision to null to disable getObjectIdCache()
		 * temporarily as otherwise getObjectId() method below will always 
		 * resolved to existing value of resolvedRevision
		 */
		resolvedRevision = null;
		resolvedRevision = getProject().getObjectId(state.blobIdent.revision);
		
		OneDev.getInstance(WebSocketManager.class).notifyObserverChange(this);
		newRevisionPicker(target);
		target.add(revisionIndexing);
		newBlobNavigator(target);
		newBlobOperations(target);
		newBlobContent(target);
		resizeWindow(target);
	}
	
	@Override
	public void renderHead(IHeaderResponse response) {
		super.renderHead(response);

		response.render(JavaScriptHeaderItem.forReference(new ProjectBlobResourceReference()));
		
		String callback = ajaxBehavior.getCallbackFunction(explicit("action")).toString();
		String script = String.format("onedev.server.projectBlob.onDomReady(%s);", callback);
		
		response.render(OnDomReadyHeaderItem.forScript(script));
		
		response.render(OnLoadHeaderItem.forScript("onedev.server.projectBlob.onWindowLoad();"));
	}

	public static ProjectBlobPage.State getState(CodeComment comment) {
		BlobIdent blobIdent = new BlobIdent(comment.getMarkPos().getCommit(), comment.getMarkPos().getPath(), 
				FileMode.REGULAR_FILE.getBits());
		ProjectBlobPage.State state = new ProjectBlobPage.State(blobIdent);
		state.commentId = comment.getId();
		state.mark = comment.getMarkPos().getRange();
		return state;
	}	
	
	public static PageParameters paramsOf(CodeComment comment) {
		return paramsOf(comment.getProject(), getState(comment));
	}	
	
	public static PageParameters paramsOf(Project project, BlobIdent blobIdent) {
		return paramsOf(project, new State(blobIdent));
	}
	
	public static void fillParams(PageParameters params, State state) {
		if (state.blobIdent.revision != null)
			params.add(PARAM_REVISION, state.blobIdent.revision);
		if (state.blobIdent.path != null)
			params.add(PARAM_PATH, state.blobIdent.path);
		if (state.mark != null)
			params.add(PARAM_MARK, state.mark.toString());
		if (state.requestId != null)
			params.add(PARAM_REQUEST, state.requestId);
		if (state.commentId != null)
			params.add(PARAM_COMMENT, state.commentId);
		if (state.mode != Mode.VIEW)
			params.add(PARAM_MODE, state.mode.name().toLowerCase());
		if (state.query != null)
			params.add(PARAM_QUERY, state.query);
		if (state.initialNewPath != null)
			params.add(PARAM_INITIAL_NEW_PATH, state.initialNewPath);
	}
	
	public static PageParameters paramsOf(Project project, State state) {
		PageParameters params = paramsOf(project);
		fillParams(params, state);
		return params;
	}
	
	private void newSearchResult(@Nullable AjaxRequestTarget target, @Nullable List<QueryHit> hits) {
		Component content;
		if (hits != null) {
			content = new SearchResultPanel("content", this, hits) {
				
				@Override
				protected void onClose(AjaxRequestTarget target) {
					newSearchResult(target, null);
					resizeWindow(target);
				}
				
			};
			if (target != null) {
				target.appendJavaScript(""
						+ "$('#project-blob>.search-result').show(); "
						+ "$('#project-blob .search-result>.body').focus();");
			}
		} else {
			content = new WebMarkupContainer("content").setOutputMarkupId(true);
			if (target != null) 
				target.appendJavaScript("$('#project-blob>.search-result').hide();");
			else 
				searchResult.add(AttributeAppender.replace("style", "display: none;"));
		}
		if (target != null) {
			searchResult.replace(content);
			target.add(content);
		} else {
			searchResult.add(content);
		}
	}
	
	@Override
	protected void onPopState(AjaxRequestTarget target, Serializable data) {
		super.onPopState(target, data);

		State popState = (State) data;
		if (!popState.blobIdent.revision.equals(state.blobIdent.revision)) {
			state = popState;
			newSearchResult(target, null);
			onResolvedRevisionChange(target);
		} else {
			state = popState;
			newBlobNavigator(target);
			newBlobOperations(target);
			newBlobContent(target);
			resizeWindow(target);
		}
	}
	
	private void resizeWindow(IPartialPageRequestHandler partialPageRequestHandler) {
		partialPageRequestHandler.appendJavaScript("$(window).resize();");
	}
	
	@Override
	public BlobIdent getBlobIdent() {
		return state.blobIdent;
	}
	
	@Override
	public TextRange getMark() {
		return state.mark;
	}

	@Override
	public void onMark(AjaxRequestTarget target, TextRange mark) {
		state.mark = mark;
		pushState(target);
	}

	@Override
	public String getMarkUrl(TextRange mark) {
		State markState = SerializationUtils.clone(state);
		markState.blobIdent.revision = resolvedRevision.name();
		markState.commentId = null;
		markState.mark = mark;
		PageParameters params = paramsOf(getProject(), markState);		
		return RequestCycle.get().urlFor(ProjectBlobPage.class, params).toString();
	}
	
	@Override
	public Mode getMode() {
		return state.mode;
	}

	@Override
	public void onSelect(AjaxRequestTarget target, BlobIdent blobIdent, @Nullable TokenPosition tokenPos) {
		TextRange prevMark = state.mark;
		state.mark = TextRange.of(tokenPos);
		if (!blobIdent.revision.equals(state.blobIdent.revision)) {
			state.blobIdent = blobIdent;
			state.mode = Mode.VIEW;
			state.commentId = null;
			state.requestId = null;
			newSearchResult(target, null);
			onResolvedRevisionChange(target);
		} else if (!Objects.equal(state.blobIdent.path, blobIdent.path)) {
			state.blobIdent.path = blobIdent.path;
			state.blobIdent.mode = blobIdent.mode;
			state.mode = Mode.VIEW;
			state.commentId = null;
			newBlobNavigator(target);
			newBlobOperations(target);
			newBlobContent(target);
			resizeWindow(target);
			OneDev.getInstance(WebSocketManager.class).notifyObserverChange(this);
		} else if (state.mark != null) {
			if (get(BLOB_CONTENT_ID) instanceof Markable) {
				// This logic is added for performance reason, we do not want to 
				// reload the file if go to different mark positions in same file
				((Markable)get(BLOB_CONTENT_ID)).mark(target, state.mark);
			} else {
				state.mode = Mode.VIEW;
				newBlobOperations(target);
				newBlobContent(target);
				resizeWindow(target);
			}
		} else if (prevMark != null) {
			state.mode = Mode.VIEW;
			newBlobOperations(target);
			newBlobContent(target);
			resizeWindow(target);
		}
		pushState(target);
	}

	@Override
	public void onSearchComplete(AjaxRequestTarget target, List<QueryHit> hits) {
		newSearchResult(target, hits);
		resizeWindow(target);
	}

	@Override
	public void onCommentOpened(AjaxRequestTarget target, CodeComment comment) {
		if (comment != null) {
			state.commentId = comment.getId();
			state.mark = Preconditions.checkNotNull(comment.mapRange(state.blobIdent));
		} else {
			state.commentId = null;
			state.mark = null;
		}
		OneDev.getInstance(WebSocketManager.class).notifyObserverChange(this);
		pushState(target);
	}

	@Override
	public void onAddComment(AjaxRequestTarget target, TextRange mark) {
		state.commentId = null;
		OneDev.getInstance(WebSocketManager.class).notifyObserverChange(this);
		state.mark = mark;
		pushState(target);
	}
	
	@Override
	public boolean isOnBranch() {
		return getProject().getBranchRef(state.blobIdent.revision) != null;
	}

	@Override
	public RevCommit getCommit() {
		return getProject().getRevCommit(resolvedRevision);
	}
	
	@Override
	public Collection<String> getWebSocketObservables() {
		Collection<String> observables = super.getWebSocketObservables();
		observables.add(CommitIndexed.getWebSocketObservable(getProject().getRevCommit(resolvedRevision).name()));
		if (state.requestId != null)
			observables.add(PullRequest.getWebSocketObservable(state.requestId));
		if (state.commentId != null)
			observables.add(CodeComment.getWebSocketObservable(state.commentId));
		
		return observables;
	}

	public static class State implements Serializable {
		
		private static final long serialVersionUID = 1L;

		public BlobIdent blobIdent;
		
		public Long requestId;
		
		public Long commentId;
		
		public TextRange mark;
		
		public Mode mode = Mode.VIEW;
		
		public String query;
		
		public String initialNewPath;

		public State(BlobIdent blobIdent) {
			this.blobIdent = blobIdent;
		}

		public State() {
			blobIdent = new BlobIdent();
		}
		
	}

	@Override
	public void onModeChange(AjaxRequestTarget target, Mode mode) {
		state.initialNewPath = null;
		
		/*
		 * User might be changing blob name when adding a file, and onModeChange will be called. 
		 * In this case, we only need to re-create blob content
		 */
		if (mode != Mode.ADD || state.mode != Mode.ADD) {
			state.mode = mode;
			pushState(target);
			if (state.mode == Mode.VIEW || state.mode == Mode.EDIT || state.mode == Mode.ADD) {
				newBlobNavigator(target);
				newBlobOperations(target);
			}
		}			
		newBlobContent(target);
		resizeWindow(target);
	}

	@Override
	public void onCommitted(AjaxRequestTarget target, RefUpdated refUpdated) {
		Project project = getProject();
		String branch = state.blobIdent.revision;
		BlobIdent newBlobIdent;
		getProject().cacheObjectId(branch, refUpdated.getNewCommitId());

		if (state.mode == Mode.DELETE) {
			try (RevWalk revWalk = new RevWalk(getProject().getRepository())) {
				RevTree revTree = getProject().getRevCommit(refUpdated.getNewCommitId()).getTree();
				String parentPath = StringUtils.substringBeforeLast(state.blobIdent.path, "/");
				while (TreeWalk.forPath(getProject().getRepository(), parentPath, revTree) == null) {
					if (parentPath.contains("/")) {
						parentPath = StringUtils.substringBeforeLast(parentPath, "/");
					} else {
						parentPath = null;
						break;
					}
				}
				newBlobIdent = new BlobIdent(branch, parentPath, FileMode.TREE.getBits());
			} catch (IOException e) {
				throw new RuntimeException(e);
			}	
		} else if (state.mode == Mode.EDIT) {
			newBlobIdent = new BlobIdent(branch, getNewPath(), FileMode.REGULAR_FILE.getBits());
		} else { 
			// We've uploaded some files
			newBlobIdent = null;
		}
		
		Subject subject = SecurityUtils.getSubject();
		Long projectId = project.getId();
		String refName = refUpdated.getRefName();
		ObjectId oldCommitId = refUpdated.getOldCommitId();
		ObjectId newCommitId = refUpdated.getNewCommitId();
		OneDev.getInstance(UnitOfWork.class).doAsync(new Runnable() {

			@Override
			public void run() {
				ThreadContext.bind(subject);
				try {
					Project project = OneDev.getInstance(ProjectManager.class).load(projectId);
					project.cacheObjectId(branch, refUpdated.getNewCommitId());
					RefUpdated refUpdated = new RefUpdated(project, refName, oldCommitId, newCommitId);
					OneDev.getInstance(ListenerRegistry.class).post(refUpdated);
				} finally {
					ThreadContext.unbindSubject();
				}
			}
			
		});
		
		if (newBlobIdent != null) {
			state.blobIdent = newBlobIdent;
			state.mark = null;
			state.commentId = null;
			state.mode = Mode.VIEW;
			onResolvedRevisionChange(target);
			pushState(target);
		} else {
			state.mode = Mode.VIEW;
			onResolvedRevisionChange(target);
		}

		// fix the issue that sometimes indexing indicator of new commit does not disappear 
		target.appendJavaScript("Wicket.WebSocket.send('RenderCallback');");	    			
	}
	
	@Override
	public String getInitialNewPath() {
		return state.initialNewPath;
	}
	
	@Override
	public String getDirectory() {
		String path;
		if (state.mode == Mode.ADD || state.mode == Mode.EDIT) {
			path = getNewPath();
			if (path != null) {
				if (path.contains("/"))
					path = StringUtils.substringBeforeLast(path, "/");
				else
					path = null;
			} else {
				throw new IllegalStateException();
			}
		} else if (state.blobIdent.isTree()) {
			path = state.blobIdent.path;
		} else if (state.blobIdent.path.contains("/")) {
			path = StringUtils.substringBeforeLast(state.blobIdent.path, "/");
		} else {
			path = null;
		}
		return path;
	}
	
	@Override
	public String getDirectoryUrl() {
		String revision = state.blobIdent.revision;
		BlobIdent blobIdent = new BlobIdent(revision, getDirectory(), FileMode.TREE.getBits());
		ProjectBlobPage.State state = new ProjectBlobPage.State(blobIdent);
		return urlFor(ProjectBlobPage.class, ProjectBlobPage.paramsOf(getProject(), state)).toString();
	}

	@Override
	public String getRootDirectoryUrl() {
		BlobIdent blobIdent = new BlobIdent(state.blobIdent.revision, null, FileMode.TREE.getBits());
		return RequestCycle.get().urlFor(ProjectBlobPage.class, 
				ProjectBlobPage.paramsOf(getProject(), blobIdent)).toString();
	}
	
	@Override
	public String getNewPath() {
		BlobNavigator blobNavigator = (BlobNavigator) get(BLOB_NAVIGATOR_ID);
		return blobNavigator.getNewPath();
	}

	@Override
	public void onEvent(IEvent<?> event) {
		super.onEvent(event);
		if (event.getPayload() instanceof RevisionResolved) {
			RevisionResolved revisionResolveEvent = (RevisionResolved) event.getPayload();
			resolvedRevision = revisionResolveEvent.getResolvedRevision();
		} else if (event.getPayload() instanceof PageDataChanged) {
			PageDataChanged pageDataChanged = (PageDataChanged) event.getPayload();
			pageDataChanged.getHandler().add(revisionIndexing);
			resizeWindow(pageDataChanged.getHandler());
		}
	}

	@Override
	public String getAutosaveKey() {
		if (state.mode == Mode.ADD) {
			return String.format("autosave:addBlob:%d:%s:%s", 
					getProject().getId(), state.blobIdent.revision, getNewPath());
		} else if (state.mode == Mode.EDIT) {
			return String.format("autosave:editBlob:%d:%s:%s:%s", 
					getProject().getId(), state.blobIdent.revision, 
					state.blobIdent.path, getProject().getBlob(state.blobIdent).getBlobId());
		} else {
			throw new IllegalStateException();
		}
	}

	@Override
	public RefUpdated uploadFiles(Collection<FileUpload> uploads, String directory, String commitMessage) {
		ProjectManager projectManager = OneDev.getInstance(ProjectManager.class);
		Map<String, BlobContent> newBlobs = new HashMap<>();
		
		String parentPath;
		BlobIdent blobIdent = getBlobIdent();
		if (blobIdent.path != null) {
			if (blobIdent.isTree()) 
				parentPath = blobIdent.path;
			else if (blobIdent.path.contains("/")) 
				parentPath = StringUtils.substringBeforeLast(blobIdent.path, "/");
			else
				parentPath = null;
		} else {
			parentPath = null;
		}
		
		if (directory != null) { 
			if (parentPath != null)
				parentPath += "/" + directory;
			else
				parentPath = directory;
		}
		
		for (FileUpload upload: uploads) {
			String blobPath = upload.getClientFileName();
			if (parentPath != null)
				blobPath = parentPath + "/" + blobPath;
			
			if (projectManager.isModificationNeedsQualityCheck(SecurityUtils.getUser(), getProject(), 
					blobIdent.revision, blobPath)) {
				throw new BlobUploadException("Adding of file '" + blobPath + "' need to be reviewed/verified. "
						+ "Please submit pull request instead");
			}
			
			BlobContent blobContent = new BlobContent.Immutable(upload.getBytes(), FileMode.REGULAR_FILE);
			newBlobs.put(blobPath, blobContent);
		}

		BlobEdits blobEdits = new BlobEdits(Sets.newHashSet(), newBlobs);
		String refName = GitUtils.branch2ref(blobIdent.revision);

		User user = Preconditions.checkNotNull(OneDev.getInstance(UserManager.class).getCurrent());

		ObjectId prevCommitId = getProject().getObjectId(blobIdent.revision);

		while (true) {
			try {
				ObjectId newCommitId = blobEdits.commit(getProject().getRepository(), refName, prevCommitId, 
						prevCommitId, user.asPerson(), commitMessage);
				return new RefUpdated(getProject(), refName, prevCommitId, newCommitId);
			} catch (ObjectAlreadyExistsException|NotTreeException e) {
				throw new BlobUploadException(e.getMessage());
			} catch (ObsoleteCommitException e) {
				prevCommitId = e.getOldCommitId();
			}
		}
	}

	@Override
	protected boolean isPermitted() {
		return SecurityUtils.canReadCode(getProject().getFacade());
	}

	@Override
	public String appendRaw(String url) {
		try {
			URIBuilder builder = new URIBuilder(url);
			for (NameValuePair pair: builder.getQueryParams()) {
				if (pair.getName().equals(PARAM_RAW))
					return url;
			}
			return builder.addParameter(PARAM_RAW, "true").build().toString();
		} catch (URISyntaxException e) {
			throw new RuntimeException(e);
		}
	}
	
}
