#!/usr/bin/env python3
"""
Orquestador Central de Ancla.
Recibe comandos en lenguaje natural e interactúa con:
- GitHub (todos los repositorios públicos de Luics415 y favoritos)
- Google Jules (CLI oficial y sesiones remotas)
- Monitor de Empleo (OCC, LinkedIn, Computrabajo, Glassdoor, Indeed vía ADB)
- Ecosistema Google (Gmail, Calendar, Meet, Drive, NotebookLM, Analytics)
"""

import sys
import re
import argparse
from pathlib import Path
from typing import Dict, Any, Optional

if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

# Importar submódulos de ancla
from github_manager import GitHubManager
from jules_bridge import JulesBridge
from job_tracker import JobTracker
from google_hub import GoogleHub
from memory_engine import MemoryEngine
from bts_mode import BTSExperience


class AnclaOrchestrator:
    def __init__(self):
        self.github = GitHubManager()
        self.jules = JulesBridge()
        self.jobs = JobTracker()
        self.google = GoogleHub()
        self.memory = MemoryEngine()
        self.bts = BTSExperience(self.memory)

    def handle_query(self, query: str) -> str:
        q = query.lower().strip()

        # Experiencia Interactiva Especial: "Unlock BTS" o "7777"
        if self.bts.is_trigger(q):
            return self.bts.activate_experience()

        # 0. Aprendizaje explícito: "Ancla, recuerda que...", "Aprende que..."
        match_learn = re.search(r"(?:recuerda\s+que|aprende\s+que|toma\s+nota\s+de\s+que|guarda\s+que)\s+(.*)", q)
        if match_learn:
            fact = match_learn.group(1).strip()
            self.memory.learn_insight(fact)
            res = f"🧠 ¡Entendido! He aprendido y guardado en mi memoria permanente:\n   «{fact}»\nLo tendré en cuenta en las próximas consultas."
            return self.bts.enhance_response(res)

        # 1. Proyectos en Stars / Favoritos
        if any(w in q for w in ["star", "stars", "favorito", "favoritos"]):
            return self.bts.enhance_response(self._handle_stars())

        # 2. Proyecto específico: "Hablame de mi proyecto X"
        match_project = re.search(r"(?:hablame|detalles|info|informacion|como va el proyecto|sobre el proyecto|proyecto)\s+(?:de\s+|de\s+mi\s+proyecto\s+)?([a-zA-Z0-9_\-\.\s]+)", q)
        if match_project and not any(k in q for k in ["como van mis proyectos", "mis proyectos"]):
            candidate = match_project.group(1).strip()
            candidate = re.sub(r"^(mi\s+proyecto\s+|el\s+proyecto\s+|mi\s+)", "", candidate).strip()
            if candidate and candidate not in ["en", "con", "favoritos", "stars"]:
                return self.bts.enhance_response(self._handle_single_project(candidate))

        # 3. Resumen general de proyectos: "Como van mis proyectos"
        if any(w in q for w in ["como van mis proyectos", "mis proyectos", "lista de proyectos", "repositorios"]):
            return self.bts.enhance_response(self._handle_projects_overview())

        # 4. Estado de búsqueda de empleo (OCC, LinkedIn, Computrabajo, etc.)
        if any(w in q for w in ["empleo", "empleos", "trabajo", "postulacion", "postulaciones", "linkedin", "occ", "computrabajo", "indeed", "glassdoor", "candidatura"]):
            return self.bts.enhance_response(self._handle_jobs())

        # 5. Ecosistema Google (Gmail, Calendar, Meet, Drive, NotebookLM, Analytics)
        if any(w in q for w in ["google", "reunion", "reuniones", "meet", "calendario", "gmail", "drive", "fotos", "notebook", "analytics"]):
            for app in ["gmail", "calendar", "drive", "photos", "meet"]:
                if f"abre {app}" in q or f"abrir {app}" in q:
                    res = self.google.open_app_on_device(app)
                    return self.bts.enhance_response(f"📱 {res['message']}")
            return self.bts.enhance_response(self.google.format_hub_summary())

        # 6. Google Jules directamente
        if "jules" in q:
            return self.bts.enhance_response(self._handle_jules())

    def _handle_projects_overview(self) -> str:
        overview = self.github.get_projects_overview()
        jules_auth = self.jules.check_auth()
        self.memory.record_interaction("projects_overview", {"count": overview["total_public_repos"]})

        lines = []
        greeting = self.memory.get_dynamic_greeting("projects")
        lines.append(greeting)
        lines.append("⚓ Ancla - Estado General de tus Proyectos")
        lines.append("=" * 65)
        lines.append(f"📦 Perfil: https://github.com/{self.github.username}")
        lines.append(f"📊 Repositorios Públicos: {overview['total_public_repos']} | ⭐ Favoritos: {overview['total_starred']}")
        lines.append(f"🤖 Google Jules: {'✅ Conectado' if jules_auth['authenticated'] else '⚠️ Sesión pendiente (`npx @google/jules login`)'}")
        lines.append("-" * 65)

        lines.append("Últimos proyectos con actividad reciente:")
        for r in overview["repos"][:8]:
            star = "⭐" if r["is_starred"] else "  "
            date_short = r["updated_at"][:10]
            lines.append(f" {star} {r['name']:<27} | {r['language']:<14} | Act: {date_short}")

        if len(overview["repos"]) > 8:
            lines.append(f"\n... y {len(overview['repos']) - 8} proyectos públicos más registrados.")

        lines.append("-" * 65)
        suggestion = self.memory.get_proactive_suggestion("projects_overview")
        lines.append(suggestion)
        return "\n".join(lines)

    def _handle_single_project(self, project_name: str) -> str:
        details = self.github.get_repo_details(project_name)
        if not details:
            return f"⚓ No encontré ningún repositorio público llamado '{project_name}' en tu perfil de GitHub ({self.github.username})."

        jules_status = self.jules.get_repo_status(details["name"])
        commits = details.get("recent_commits", [])
        latest_commit_msg = commits[0]["commit"]["message"].split("\n")[0] if commits else "Sin commits"

        delta_info = self.memory.evaluate_project_delta(details["name"], latest_commit_msg, details.get("updated_at", ""))
        self.memory.record_interaction("single_project", {"repo": details["name"]})

        lines = []
        lines.append(f"⚓ Proyecto: {details['name']}")
        lines.append("=" * 65)
        lines.append(f"📝 Descripción: {details.get('description') or 'Sin descripción proporcionada.'}")
        lines.append(f"💻 Lenguaje Principal: {details.get('language') or 'No especificado'}")
        lines.append(f"⭐ Estrellas: {details.get('stargazers_count', 0)} | 🍴 Forks: {details.get('forks_count', 0)}")
        lines.append(f"🔗 Repositorio: {details.get('html_url')}")
        lines.append(f"📅 Última actualización en GitHub: {details.get('updated_at', '')[:10]}")
        lines.append("-" * 65)

        if delta_info:
            lines.append(f"🧠 Memoria de Ancla:")
            lines.append(f"   {delta_info}")
            lines.append("-" * 65)

        lines.append(f"🤖 Estado en Google Jules:")
        lines.append(f"   {jules_status['note']}")

        lines.append("-" * 65)
        lines.append("📜 Últimos Commits:")
        if commits:
            for c in commits:
                msg = c["commit"]["message"].split("\n")[0]
                date_str = c["commit"]["author"]["date"][:10]
                lines.append(f"   - [{date_str}] {msg}")
        else:
            lines.append("   (No hay commits recientes disponibles)")

        lines.append("-" * 65)
        suggestion = self.memory.get_proactive_suggestion("single_project", {"repo": details["name"]})
        lines.append(suggestion)

        return "\n".join(lines)

    def _handle_stars(self) -> str:
        starred = self.github.get_starred_repos()
        self.memory.record_interaction("stars", {"count": len(starred)})
        lines = []
        lines.append(f"⭐ Proyectos en Stars / Favoritos ({len(starred)}):")
        lines.append("=" * 65)
        for r in starred:
            lang = r.get("language") or "General"
            lines.append(f" ⭐ {r['name']:<28} | {lang:<14} | {r['html_url']}")
        lines.append("-" * 65)
        lines.append("💡 Puedes pedirme detalles de cualquiera de ellos diciendo: 'Háblame de [Nombre]'.")
        return "\n".join(lines)

    def _handle_jobs(self) -> str:
        self.memory.record_interaction("jobs", {})
        report_text = self.jobs.format_cli_report()
        suggestion = self.memory.get_proactive_suggestion("jobs")
        return f"{report_text}\n\n{suggestion}"

    def _handle_jules(self) -> str:
        auth = self.jules.check_auth()
        lines = []
        lines.append("🤖 Google Jules - Asistente de Codificación en Repositorios")
        lines.append("=" * 65)
        lines.append(f"Estado: {'✅ Autenticado y Listo' if auth['authenticated'] else '⚠️ Requiere Inicio de Sesión'}")
        lines.append(f"Mensaje: {auth['message']}")
        lines.append("-" * 65)
        lines.append("Comandos de Jules disponibles:")
        lines.append(" 1. `npx @google/jules login` (Inicia sesión con tu cuenta de Google)")
        lines.append(" 2. `npx @google/jules new --repo Luics415/[repo] \"[tarea]\"` (Crea una tarea)")
        lines.append(" 3. `npx @google/jules remote list --session` (Lista tareas activas)")
        lines.append(" 4. `npx @google/jules remote pull --session [id]` (Aplica los cambios)")
        return "\n".join(lines)

    def _handle_default_welcome(self) -> str:
        lines = []
        lines.append("⚓ Hola, soy Ancla. Estoy lista para asistirte.")
        lines.append("-" * 60)
        lines.append("Puedes consultarme:")
        lines.append(" • 'Ancla, ¿cómo van mis proyectos?'")
        lines.append(" • 'Háblame de mi proyecto QRVoxelStudio (o Dev-Visualizer, AnchorGrid, etc.)'")
        lines.append(" • '¿Qué proyectos se encuentran en Stars/Favoritos?'")
        lines.append(" • '¿Cómo van mis cuentas de buscar empleo?' (OCC, LinkedIn, Computrabajo, etc.)")
        lines.append(" • 'Revisa mis reuniones de Google Meet y calendario de hoy'")
        lines.append(" • '¿Cuál es el estado de Google Jules?'")
        return "\n".join(lines)


def main():
    orchestrator = AnclaOrchestrator()
    if len(sys.argv) > 1:
        query = " ".join(sys.argv[1:])
        print(orchestrator.handle_query(query))
    else:
        print(orchestrator._handle_default_welcome())


if __name__ == "__main__":
    main()
