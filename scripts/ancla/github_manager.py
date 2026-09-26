#!/usr/bin/env python3
"""
Módulo de Gestión de GitHub para Ancla.
Permite consultar todos los repositorios públicos de Luics415,
repositorios favoritos (Stars), detalles específicos de proyectos y actividad reciente.
"""

import sys
import json
import urllib.request
import urllib.error
from typing import List, Dict, Any, Optional

if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

DEFAULT_USER = "Luics415"
USER_AGENT = "Ancla-Assistant/1.0"


class GitHubManager:
    def __init__(self, username: str = DEFAULT_USER, token: Optional[str] = None):
        self.username = username
        self.token = token

    def _make_request(self, endpoint: str) -> Any:
        url = f"https://api.github.com/{endpoint.lstrip('/')}"
        headers = {
            "User-Agent": USER_AGENT,
            "Accept": "application/vnd.github.v3+json"
        }
        if self.token:
            headers["Authorization"] = f"token {self.token}"

        req = urllib.request.Request(url, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=15) as resp:
                data = resp.read().decode("utf-8")
                return json.loads(data)
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None
            print(f"[Error GitHub API] HTTP {e.code}: {e.reason}", file=sys.stderr)
            return None
        except Exception as e:
            print(f"[Error GitHub API] {e}", file=sys.stderr)
            return None

    def get_public_repos(self) -> List[Dict[str, Any]]:
        """Obtiene la lista de todos los repositorios públicos del usuario."""
        repos = self._make_request(f"users/{self.username}/repos?per_page=100&sort=updated")
        if not repos or not isinstance(repos, list):
            return []
        return repos

    def get_starred_repos(self) -> List[Dict[str, Any]]:
        """Obtiene los repositorios que el usuario ha marcado con Star/Favorito."""
        starred = self._make_request(f"users/{self.username}/starred?per_page=100")
        if not starred or not isinstance(starred, list):
            return []
        return starred

    def find_repo(self, query: str) -> Optional[Dict[str, Any]]:
        """Busca un repositorio por nombre con tolerancia a mayúsculas, espacios y guiones."""
        repos = self.get_public_repos()
        query_norm = query.lower().replace("-", "").replace("_", "").replace(" ", "")

        # 1. Búsqueda exacta normalizada
        for r in repos:
            name_norm = r["name"].lower().replace("-", "").replace("_", "").replace(" ", "")
            if name_norm == query_norm:
                return r

        # 2. Búsqueda parcial (contiene)
        for r in repos:
            name_norm = r["name"].lower().replace("-", "").replace("_", "").replace(" ", "")
            if query_norm in name_norm or name_norm in query_norm:
                return r

        return None

    def get_repo_details(self, repo_name: str) -> Optional[Dict[str, Any]]:
        """Obtiene información completa y últimos commits de un repositorio específico."""
        matched = self.find_repo(repo_name)
        exact_name = matched["name"] if matched else repo_name

        repo_info = self._make_request(f"repos/{self.username}/{exact_name}")
        if not repo_info or not isinstance(repo_info, dict):
            return None

        # Obtener últimos commits
        commits = self._make_request(f"repos/{self.username}/{exact_name}/commits?per_page=3")
        repo_info["recent_commits"] = commits if isinstance(commits, list) else []

        # Obtener lenguajes usados
        languages = self._make_request(f"repos/{self.username}/{exact_name}/languages")
        repo_info["languages_breakdown"] = languages if isinstance(languages, dict) else {}

        return repo_info

    def get_projects_overview(self) -> Dict[str, Any]:
        """Genera un reporte resumido de todos los proyectos activos."""
        repos = self.get_public_repos()
        starred = self.get_starred_repos()
        starred_names = {r["name"] for r in starred}

        summary = {
            "total_public_repos": len(repos),
            "total_starred": len(starred),
            "starred_list": [r["name"] for r in starred],
            "repos": []
        }

        for r in repos:
            summary["repos"].append({
                "name": r["name"],
                "stars": r["stargazers_count"],
                "language": r["language"] or "No especificado",
                "description": r["description"] or "Sin descripción",
                "updated_at": r["updated_at"],
                "is_starred": r["name"] in starred_names,
                "html_url": r["html_url"]
            })

        return summary


def main():
    manager = GitHubManager()
    if len(sys.argv) > 1:
        cmd = sys.argv[1].lower()
        if cmd in ("--overview", "-o", "overview"):
            overview = manager.get_projects_overview()
            print(f"⚓ Proyectos de {DEFAULT_USER}: {overview['total_public_repos']} públicos, {overview['total_starred']} en favoritos.")
            for r in overview["repos"][:10]:
                star_icon = "⭐" if r["is_starred"] else " "
                print(f" {star_icon} {r['name']:<28} | {r['language']:<12} | {r['updated_at'][:10]} | {r['html_url']}")
            if len(overview["repos"]) > 10:
                print(f" ... y {len(overview['repos']) - 10} repositorios más.")
            return

        if cmd in ("--starred", "-s", "starred"):
            starred = manager.get_starred_repos()
            print(f"⭐ Repositorios Favoritos ({len(starred)}):")
            for r in starred:
                print(f" - {r['name']} ({r.get('language', 'N/A')}) -> {r['html_url']}")
            return

        if cmd in ("--repo", "-r") and len(sys.argv) > 2:
            target = sys.argv[2]
            details = manager.get_repo_details(target)
            if not details:
                print(f"No se encontró el repositorio '{target}' en el perfil de {DEFAULT_USER}.")
                return
            print(f"📁 Proyecto: {details['name']}")
            print(f"📝 Descripción: {details.get('description', 'N/A')}")
            print(f"💻 Lenguaje principal: {details.get('language', 'N/A')}")
            print(f"⭐ Estrellas: {details.get('stargazers_count', 0)}")
            print(f"🔗 URL: {details.get('html_url')}")
            print(f"📅 Última actualización: {details.get('updated_at')}")
            print("\nÚltimos commits:")
            for c in details.get("recent_commits", []):
                msg = c["commit"]["message"].split("\n")[0]
                date = c["commit"]["author"]["date"][:10]
                print(f" - [{date}] {msg}")
            return

    # Comportamiento por defecto
    overview = manager.get_projects_overview()
    print(f"Total repositorios públicos: {overview['total_public_repos']}")
    print(f"Total repositorios con Star: {overview['total_starred']}")


if __name__ == "__main__":
    main()
