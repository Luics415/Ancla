#!/usr/bin/env python3
"""
Puente de Integración con Google Jules para Ancla.
Permite interactuar con la CLI oficial de Jules (@google/jules)
para consultar sesiones remotas, tareas en curso y asignar nuevos trabajos a repositorios.
"""

import sys
import shutil
import subprocess
from typing import Dict, Any, List, Optional

if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass


class JulesBridge:
    def __init__(self, executable: Optional[str] = None):
        # Si 'jules' está instalado globalmente en el PATH, usarlo; si no, npx @google/jules
        self.cmd_prefix = self._resolve_executable(executable)

    def _resolve_executable(self, custom: Optional[str]) -> List[str]:
        if custom:
            return [custom]
        if shutil.which("jules"):
            return ["jules"]
        return ["npx.cmd" if sys.platform == "win32" else "npx", "--yes", "@google/jules"]

    def _run_jules(self, args: List[str], timeout: int = 30) -> subprocess.CompletedProcess:
        cmd = self.cmd_prefix + args
        try:
            return subprocess.run(
                cmd,
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=timeout
            )
        except subprocess.TimeoutExpired:
            return subprocess.CompletedProcess(
                args=cmd,
                returncode=-1,
                stdout="",
                stderr="Timeout al comunicarse con Google Jules CLI."
            )
        except Exception as e:
            return subprocess.CompletedProcess(
                args=cmd,
                returncode=-1,
                stdout="",
                stderr=str(e)
            )

    def check_auth(self) -> Dict[str, Any]:
        """Verifica si la CLI de Jules tiene una sesión iniciada con Google."""
        proc = self._run_jules(["remote", "list", "--repo"], timeout=15)
        raw = (proc.stdout + proc.stderr).strip()

        if "did you forget to login" in raw.lower() or "without a valid client" in raw.lower():
            return {
                "authenticated": False,
                "message": "Sesión no iniciada. Ejecuta 'npx @google/jules login' para iniciar sesión.",
                "raw": raw
            }

        if proc.returncode == 0:
            return {
                "authenticated": True,
                "message": "Autenticado correctamente con Google Jules.",
                "raw": raw
            }

        return {
            "authenticated": False,
            "message": f"Estado indeterminado (code {proc.returncode}): {raw}",
            "raw": raw
        }

    def list_sessions(self) -> List[Dict[str, str]]:
        """Lista las sesiones remotas de Jules."""
        auth = self.check_auth()
        if not auth["authenticated"]:
            return []

        proc = self._run_jules(["remote", "list", "--session"], timeout=20)
        lines = proc.stdout.splitlines()
        sessions = []
        for line in lines:
            line_str = line.strip()
            if line_str and not line_str.startswith("Error") and not line_str.startswith("Tip"):
                sessions.append({"raw": line_str})
        return sessions

    def list_repos(self) -> List[str]:
        """Lista los repositorios vinculados en Jules."""
        auth = self.check_auth()
        if not auth["authenticated"]:
            return []

        proc = self._run_jules(["remote", "list", "--repo"], timeout=20)
        lines = proc.stdout.splitlines()
        repos = []
        for line in lines:
            line_str = line.strip()
            if line_str and not line_str.startswith("Error") and not line_str.startswith("Tip"):
                repos.append(line_str)
        return repos

    def get_repo_status(self, repo_name: str) -> Dict[str, Any]:
        """Consulta si hay tareas o sesiones activas para un repositorio específico."""
        auth = self.check_auth()
        if not auth["authenticated"]:
            return {
                "repo": repo_name,
                "has_jules_active": False,
                "authenticated": False,
                "note": "Inicia sesión con 'npx @google/jules login' para sincronizar con este repositorio."
            }

        sessions = self.list_sessions()
        matching_sessions = [s for s in sessions if repo_name.lower() in s["raw"].lower()]

        return {
            "repo": repo_name,
            "has_jules_active": len(matching_sessions) > 0,
            "authenticated": True,
            "sessions": matching_sessions,
            "note": f"{len(matching_sessions)} sesiones encontradas en Jules." if matching_sessions else "Sin tareas activas de Jules en este repositorio."
        }

    def assign_task(self, repo_name: str, prompt: str) -> Dict[str, Any]:
        """Asigna una nueva tarea a Google Jules para un repositorio."""
        auth = self.check_auth()
        if not auth["authenticated"]:
            return {
                "success": False,
                "message": auth["message"]
            }

        proc = self._run_jules(["new", "--repo", repo_name, prompt], timeout=45)
        if proc.returncode == 0:
            return {
                "success": True,
                "output": proc.stdout.strip(),
                "message": f"Tarea asignada a Jules exitosamente en {repo_name}."
            }
        return {
            "success": False,
            "output": (proc.stdout + proc.stderr).strip(),
            "message": f"Fallo al asignar tarea a Jules (code {proc.returncode})."
        }


def main():
    bridge = JulesBridge()
    auth = bridge.check_auth()
    print("🤖 Conexión con Google Jules:")
    print(f" - Estado de Autenticación: {'✅ Conectado' if auth['authenticated'] else '⚠️ Requiere Login'}")
    print(f" - Detalle: {auth['message']}")

    if len(sys.argv) > 1:
        cmd = sys.argv[1].lower()
        if cmd in ("--sessions", "-s"):
            sessions = bridge.list_sessions()
            print(f"Sesiones encontradas: {len(sessions)}")
            for s in sessions:
                print(f" - {s['raw']}")
        elif cmd in ("--repos", "-r"):
            repos = bridge.list_repos()
            print(f"Repositorios conectados en Jules: {len(repos)}")
            for r in repos:
                print(f" - {r}")


if __name__ == "__main__":
    main()
