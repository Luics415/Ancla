#!/usr/bin/env python3
"""
Motor de Memoria Dinámica y Aprendizaje Continuo para Ancla.
Permite a Ancla:
1. Recordar interacciones previas y preferencias del usuario.
2. Detectar cambios (deltas) desde la última consulta (nuevos commits, cambios en Jules, nuevas notificaciones).
3. Variar el tono, estructura y estilo de las respuestas para evitar respuestas mecánicas o repetitivas.
4. Proponer sugerencias contextuales e insights proactivos basados en lo que va aprendiendo.
"""

import sys
import json
import random
from pathlib import Path
from datetime import datetime
from typing import Dict, Any, List, Optional

if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

DATA_DIR = Path(__file__).resolve().parent.parent.parent / "docs" / "data"
MEMORY_FILE = DATA_DIR / "ancla_memory.json"


class MemoryEngine:
    def __init__(self):
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        self.memory = self._load_memory()

    def _load_memory(self) -> Dict[str, Any]:
        if MEMORY_FILE.exists():
            try:
                with open(MEMORY_FILE, "r", encoding="utf-8") as f:
                    return json.load(f)
            except Exception:
                pass
        return {
            "profile": "Luics415",
            "created_at": datetime.now().isoformat(),
            "query_counter": 0,
            "learned_insights": [
                "El usuario administra 24 repositorios públicos y 16 en favoritos.",
                "Cuenta de Google Jules vinculada con alerivera877@gmail.com con 31 repositorios.",
                "Dispositivo móvil principal: Vivo V2314 con aplicaciones de empleo y chat.mural.android.",
                "Proyectos clave de alta atención: QRVoxelStudio, Dev-Visualizer, AnchorGrid, MenuOrders."
            ],
            "project_snapshots": {},
            "job_snapshots": {},
            "interaction_history": []
        }

    def _save_memory(self):
        try:
            with open(MEMORY_FILE, "w", encoding="utf-8") as f:
                json.dump(self.memory, f, indent=2, ensure_ascii=False)
        except Exception as e:
            print(f"[Error guardando memoria de Ancla]: {e}", file=sys.stderr)

    def learn_insight(self, insight: str):
        """Agrega un nuevo aprendizaje a la memoria a largo plazo."""
        if insight not in self.memory["learned_insights"]:
            self.memory["learned_insights"].append(insight)
            self._save_memory()

    def record_interaction(self, query_type: str, details: Dict[str, Any]):
        """Registra la consulta actual y actualiza contadores."""
        self.memory["query_counter"] = self.memory.get("query_counter", 0) + 1
        history_entry = {
            "timestamp": datetime.now().isoformat(),
            "query_type": query_type,
            "details": details
        }
        self.memory["interaction_history"].append(history_entry)
        # Mantener historial acotado a las últimas 50 consultas
        if len(self.memory["interaction_history"]) > 50:
            self.memory["interaction_history"] = self.memory["interaction_history"][-50:]
        self._save_memory()

    def get_dynamic_greeting(self, context: str = "general") -> str:
        """Genera un saludo dinámico y variado según la hora y la recurrencia."""
        count = self.memory.get("query_counter", 0)
        hour = datetime.now().hour

        if 5 <= hour < 12:
            time_greeting = "Buenos días"
        elif 12 <= hour < 19:
            time_greeting = "Buenas tardes"
        else:
            time_greeting = "Buenas noches"

        variations = [
            f"⚓ {time_greeting}, Luis. Aquí tienes el reporte actualizado:",
            f"⚓ ¡Hola de nuevo! Analicé las novedades para ti:",
            f"⚓ Qué tal, Luis. Revisé los últimos cambios en tus sistemas:",
            f"⚓ {time_greeting}. Esto es lo que está pasando en tus proyectos:",
            f"⚓ Ancla en línea. He contrastado la información reciente con lo que vimos antes:"
        ]
        return random.choice(variations)

    def evaluate_project_delta(self, repo_name: str, latest_commit_msg: str, updated_at: str) -> Optional[str]:
        """Detecta si hay novedades o si el proyecto se mantiene igual desde la última revisión."""
        snapshots = self.memory.setdefault("project_snapshots", {})
        prev = snapshots.get(repo_name)

        snapshots[repo_name] = {
            "last_commit": latest_commit_msg,
            "updated_at": updated_at,
            "last_checked": datetime.now().isoformat(),
            "check_count": (prev.get("check_count", 0) + 1) if prev else 1
        }
        self._save_memory()

        if not prev:
            return "📌 Es la primera vez que revisamos a detalle este repositorio en esta sesión."

        if prev.get("last_commit") != latest_commit_msg:
            return f"🔥 ¡Hay un nuevo commit desde tu última consulta! '{latest_commit_msg}'"
        else:
            checks = prev.get("check_count", 1)
            if checks > 2:
                return f"ℹ️ Sin cambios de código desde hace {checks} consultas. El repositorio está estable."
            return "ℹ️ El código se mantiene en el mismo estado desde la última consulta."

    def evaluate_jobs_delta(self, current_events: Dict[str, int]) -> List[str]:
        """Detecta variaciones en las notificaciones y eventos laborales."""
        snapshots = self.memory.setdefault("job_snapshots", {})
        deltas = []

        for portal, count in current_events.items():
            prev_count = snapshots.get(portal, count)
            if count > prev_count:
                diff = count - prev_count
                deltas.append(f"🔔 ¡{diff} nuevo(s) evento(s) en {portal.capitalize()} detectados en tu teléfono!")
            snapshots[portal] = count

        self._save_memory()
        return deltas

    def get_proactive_suggestion(self, topic: str, extra_data: Optional[Dict[str, Any]] = None) -> str:
        """Genera sugerencias contextuales y aprendizajes evolutivos."""
        suggestions_by_topic = {
            "projects_overview": [
                "💡 Sugerencia: ¿Quieres que le pida a Google Jules que revise si faltan pruebas unitarias en tu repositorio más reciente?",
                "💡 Observación: Dev-Visualizer y QRVoxelStudio tuvieron actividad en septiembre. ¿Te gustaría preparar un release note conjunto?",
                "💡 Consejo: Recuerda que puedes pedirme 'Háblame de mi proyecto X' para ver commits específicos y pedirle a Jules una tarea directa."
            ],
            "single_project": [
                "💡 ¿Te gustaría que le asigne una tarea a Jules para crear tests o refactorizar este proyecto?",
                "💡 Si este proyecto tiene documentación o notas, podemos sincronizarlo con tu carpeta de NotebookLM en Drive.",
                "💡 Puedes pedirme que abra el repositorio en GitHub o ver los issues abiertos."
            ],
            "jobs": [
                "💡 Tip: Si recibes una invitación a entrevista por Meet o Calendar, dímelo y la sincronizo de inmediato en tu agenda.",
                "💡 Estrategia: Recuerda que puedes registrar nuevas postulaciones activas para que Ancla les dé seguimiento por fecha.",
                "💡 Consejo: Puedes pedirme 'abre LinkedIn en mi celular' para responder rápidamente a los mensajes de reclutadores."
            ]
        }
        options = suggestions_by_topic.get(topic, [
            "💡 Estoy registrando tus preferencias para personalizar cada vez más mis respuestas."
        ])
        return random.choice(options)


def main():
    engine = MemoryEngine()
    print("🧠 Memoria de Ancla:")
    print(f" - Consultas totales registradas: {engine.memory.get('query_counter', 0)}")
    print(f" - Aprendizajes acumulados ({len(engine.memory.get('learned_insights', []))}):")
    for ins in engine.memory.get("learned_insights", []):
        print(f"   • {ins}")


if __name__ == "__main__":
    main()
