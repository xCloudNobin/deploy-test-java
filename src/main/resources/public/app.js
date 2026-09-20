/* Java Taskboard client. Renders only via textContent (never innerHTML with
   user data), so API values cannot inject markup. */
(() => {
  "use strict";

  const $ = (sel) => document.querySelector(sel);

  const els = {
    health: $("#health"),
    release: $("#release"),
    projectForm: $("#project-form"),
    projectName: $("#project-name"),
    projectDesc: $("#project-desc"),
    projectError: $("#project-error"),
    projectSelect: $("#project-select"),
    taskForm: $("#task-form"),
    taskTitle: $("#task-title"),
    taskDesc: $("#task-desc"),
    taskStatus: $("#task-status"),
    taskPriority: $("#task-priority"),
    taskError: $("#task-error"),
    search: $("#search"),
    filterStatus: $("#filter-status"),
    filterPriority: $("#filter-priority"),
    taskCount: $("#task-count"),
    taskList: $("#task-list"),
    taskEmpty: $("#task-empty"),
  };

  let projects = [];
  let activeProjectId = null;
  const tasksById = new Map();

  async function api(path, options = {}) {
    const res = await fetch(path, {
      headers: { "content-type": "application/json" },
      ...options,
    });
    let body = null;
    try {
      body = await res.json();
    } catch {
      body = null;
    }
    if (!res.ok) {
      const err = new Error(body && body.error ? body.error : `HTTP ${res.status}`);
      err.status = res.status;
      err.fields = body && body.fields ? body.fields : null;
      throw err;
    }
    return body;
  }

  function showError(el, message) {
    if (message) {
      el.textContent = message;
      el.hidden = false;
    } else {
      el.hidden = true;
    }
  }

  function escapeText(value) {
    return String(value == null ? "" : value);
  }

  async function loadHealth() {
    try {
      const meta = await api("/api/meta");
      els.release.textContent = `release ${meta.release}`;
    } catch {
      els.release.textContent = "release unknown";
    }
  }

  async function loadProjects() {
    const data = await api("/api/projects");
    projects = data.projects || [];
    els.projectSelect.textContent = "";
    for (const project of projects) {
      const option = document.createElement("option");
      option.value = String(project.id);
      option.textContent = `${project.name} (${project.task_total ?? 0} tasks)`;
      els.projectSelect.appendChild(option);
    }
    if (!activeProjectId || !projects.some((p) => p.id === activeProjectId)) {
      activeProjectId = projects.length ? projects[0].id : null;
    }
    if (activeProjectId != null) {
      els.projectSelect.value = String(activeProjectId);
    }
  }

  function setHealth(state) {
    const text = state === "ready" ? "db ready" : state === "alive" ? "alive" : state;
    els.health.textContent = text;
    els.health.className = `badge health ${state}`;
  }

  async function refreshHealth() {
    try {
      await api("/api/health/ready");
      setHealth("ready");
    } catch {
      try {
        await api("/api/health/live");
        setHealth("alive");
      } catch {
        setHealth("down");
      }
    }
  }

  function taskMeta(task) {
    const parts = [];
    const statusText = {
      todo: "To do",
      in_progress: "In progress",
      done: "Done",
    }[task.status] || task.status;
    const priorityText = {
      low: "Low",
      medium: "Medium",
      high: "High",
    }[task.priority] || task.priority;
    const project = projects.find((p) => p.id === task.project_id);
    parts.push(statusText);
    parts.push(priorityText);
    if (project) parts.push(project.name);
    return parts.join(" · ");
  }

  async function loadSelectedTasks() {
    const params = new URLSearchParams();
    if (activeProjectId != null) params.set("project_id", String(activeProjectId));
    const q = els.search.value.trim();
    if (q) params.set("q", q);
    const status = els.filterStatus.value;
    if (status) params.set("status", status);
    const priority = els.filterPriority.value;
    if (priority) params.set("priority", priority);

    const data = await api(`/api/tasks?${params.toString()}`);
    const tasks = data.tasks || [];
    tasksById.clear();
    for (const task of tasks) tasksById.set(task.id, task);
    renderTasks(tasks);
  }

  function renderTasks(tasks) {
    els.taskCount.textContent = `${tasks.length} task${tasks.length === 1 ? "" : "s"}`;
    els.taskList.textContent = "";
    els.taskEmpty.hidden = tasks.length > 0;

    const template = $("#task-template");
    for (const task of tasks) {
      const frag = template.content.cloneNode(true);
      const li = frag.querySelector("li");

      const titleEl = frag.querySelector(".task-title");
      titleEl.textContent = escapeText(task.title);
      const metaEl = frag.querySelector(".task-meta");
      metaEl.textContent = taskMeta(task);
      const descEl = frag.querySelector(".task-desc");
      descEl.textContent = escapeText(task.description);

      const editBtn = frag.querySelector(".edit");
      const deleteBtn = frag.querySelector(".delete");
      const editForm = frag.querySelector(".task-editform");
      const editTitle = frag.querySelector(".edit-title");
      const editDesc = frag.querySelector(".edit-desc");
      const editStatus = frag.querySelector(".edit-status");
      const editPriority = frag.querySelector(".edit-priority");
      const editError = frag.querySelector(".edit-error");
      const cancelBtn = frag.querySelector(".cancel");

      function beginEdit() {
        editTitle.value = task.title;
        editDesc.value = task.description || "";
        editStatus.value = task.status;
        editPriority.value = task.priority;
        editForm.hidden = false;
        editBtn.disabled = true;
        deleteBtn.disabled = true;
        showError(editError, null);
      }

      function endEdit() {
        editForm.hidden = true;
        editBtn.disabled = false;
        deleteBtn.disabled = false;
        showError(editError, null);
      }

      editBtn.addEventListener("click", beginEdit);
      cancelBtn.addEventListener("click", endEdit);

      editForm.addEventListener("submit", async (event) => {
        event.preventDefault();
        const payload = {
          title: editTitle.value,
          description: editDesc.value,
          status: editStatus.value,
          priority: editPriority.value,
        };
        try {
          await api(`/api/tasks/${task.id}`, {
            method: "PATCH",
            body: JSON.stringify(payload),
          });
          endEdit();
          await refreshAll();
        } catch (err) {
          showError(editError, err.fields ? Object.values(err.fields).join("; ") : err.message);
        }
      });

      deleteBtn.addEventListener("click", async () => {
        try {
          await api(`/api/tasks/${task.id}`, { method: "DELETE" });
          await refreshAll();
        } catch (err) {
          showError(els.taskError, err.message);
        }
      });

      els.taskList.appendChild(frag);
    }
  }

  async function refreshAll() {
    try {
      await loadProjects();
      await loadSelectedTasks();
      await refreshHealth();
    } catch (err) {
      showError(els.taskError, err.message);
    }
  }

  els.projectForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    const payload = {
      name: els.projectName.value,
      description: els.projectDesc.value,
    };
    try {
      await api("/api/projects", { method: "POST", body: JSON.stringify(payload) });
      els.projectName.value = "";
      els.projectDesc.value = "";
      showError(els.projectError, null);
      activeProjectId = null;
      await refreshAll();
    } catch (err) {
      showError(els.projectError, err.fields ? Object.values(err.fields).join("; ") : err.message);
    }
  });

  els.taskForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (activeProjectId == null) {
      showError(els.taskError, "Create a project first.");
      return;
    }
    const payload = {
      project_id: activeProjectId,
      title: els.taskTitle.value,
      description: els.taskDesc.value,
      status: els.taskStatus.value,
      priority: els.taskPriority.value,
    };
    try {
      await api("/api/tasks", { method: "POST", body: JSON.stringify(payload) });
      els.taskTitle.value = "";
      els.taskDesc.value = "";
      showError(els.taskError, null);
      await refreshAll();
    } catch (err) {
      showError(els.taskError, err.fields ? Object.values(err.fields).join("; ") : err.message);
    }
  });

  els.projectSelect.addEventListener("change", () => {
    activeProjectId = Number(els.projectSelect.value);
    loadSelectedTasks().catch((err) => showError(els.taskError, err.message));
  });

  let searchTimer = null;
  els.search.addEventListener("input", () => {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(() => {
      loadSelectedTasks().catch((err) => showError(els.taskError, err.message));
    }, 200);
  });

  els.filterStatus.addEventListener("change", () => {
    loadSelectedTasks().catch((err) => showError(els.taskError, err.message));
  });
  els.filterPriority.addEventListener("change", () => {
    loadSelectedTasks().catch((err) => showError(els.taskError, err.message));
  });

  loadHealth().finally(() => {
    refreshAll().catch(() => {
      setHealth("down");
    });
  });
  setInterval(() => refreshHealth().catch(() => {}), 10000);
})();