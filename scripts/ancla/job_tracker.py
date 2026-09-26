#!/usr/bin/env python3
"""
Monitor Laboral Multicanal para Ancla.
Rastrea el estado de tus cuentas de búsqueda de empleo:
- OCC Mundial (mx.com.occ)
- Computrabajo (com.redarbor.computrabajo)
- LinkedIn (com.linkedin.android)
- Glassdoor (com.glassdoor.app)
- Indeed (com.indeed.android.jobsearch)

Extrae eventos y notificaciones activas del teléfono conectado vía ADB
y administra el historial de postulaciones.
"""

import sys
import re
import json
import shutil
import subprocess
from datetime import datetime
from pathlib import Path
from typing import Dict, Any, List, Optional

if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

PORTALS = {
    "linkedin": {
        "name": "LinkedIn",
        "package": "com.linkedin.android",
        "icon": "💼",
        "keywords": ["visto", "postulacion", "candidatura", "reclutador", "mensaje", "empleo", "contactado"]
    },
    "occ": {
        "name": "OCC Mundial",
        "package": "mx.com.occ",
        "icon": "📄",
        "keywords": ["visto", "descargo", "cv", "evaluacion", "postulacion", "empresa", "vacante"]
    },
    "computrabajo": {
        "name": "Computrabajo",
        "package": "com.redarbor.computrabajo",
        "icon": "🏢",
        "keywords": ["visto", "proceso", "descartado", "finalista", "candidatura", "oferta", "evaluacion"]
    },
    "indeed": {
        "name": "Indeed",
        "package": "com.indeed.android.jobsearch",
        "icon": "🔍",
        "keywords": ["solicitud", "actualizacion", "empleador", "entrevista", "empleo", "postulado"]
    },
    "glassdoor": {
        "name": "Glassdoor",
        "package": "com.glassdoor.app",
        "icon": "🚪",
        "keywords": ["candidatura", "salario", "entrevista", "actualizacion", "oferta"]
    }
}

DATA_DIR = Path(__file__).resolve().parent.parent.parent / "docs" / "data"
APPLICATIONS_FILE = DATA_DIR / "job_applications.json"


class JobTracker:
    def __init__(self):
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        if not APPLICATIONS_FILE.exists():
            self._init_applications_file()

    def _init_applications_file(self):
        initial_data = {
            "last_updated": datetime.now().isoformat(),
            "applications": [
                {
                    "company": "Ejemplo Tech",
                    "role": "Full Stack / AI Developer",
                    "portal": "LinkedIn",
                    "status": "Postulado",
                    "date": "2026-09-24",
                    "notes": "Postulación activa con seguimiento."
                }
            ]
        }
        with open(APPLICATIONS_FILE, "w", encoding="utf-8") as f:
            json.dump(initial_data, f, indent=2, ensure_ascii=False)

    def _run_adb(self, args: List[str], timeout: int = 15) -> Optional[str]:
        if not shutil.which("adb"):
            return None
        try:
            cmd = ["adb"] + args
            proc = subprocess.run(
                cmd,
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=timeout
            )
            if proc.returncode == 0:
                return proc.stdout
            return None
        except Exception:
            return None

    def get_connected_device(self) -> Optional[str]:
        """Verifica si hay un dispositivo conectado por ADB."""
        output = self._run_adb(["devices"])
        if not output:
            return None
        for line in output.splitlines():
            line = line.strip()
            if line and not line.startswith("List of") and "\tdevice" in line:
                return line.split("\t")[0]
        return None

    def get_device_notifications(self) -> Dict[str, List[Dict[str, Any]]]:
        """Extrae notificaciones activas del teléfono para las apps de empleo."""
        device_id = self.get_connected_device()
        if not device_id:
            return {}

        raw = self._run_adb(["shell", "dumpsys", "notification", "--noredact"], timeout=20)
        if not raw:
            return {}

        results: Dict[str, List[Dict[str, Any]]] = {key: [] for key in PORTALS.keys()}

        # Patrones de búsqueda en NotificationRecord
        # Bloques que contienen el pkg de la app
        records = re.split(r"NotificationRecord\(", raw)
        for record in records:
            if not record.strip():
                continue

            for key, config in PORTALS.items():
                pkg = config["package"]
                if f"pkg={pkg}" in record:
                    # Extraer title, text, subText, bigText
                    title_match = re.search(r"android\.title=String \((.*?)\)", record)
                    text_match = re.search(r"android\.text=String \((.*?)\)", record)
                    big_text_match = re.search(r"android\.bigText=String \((.*?)\)", record)
                    sub_match = re.search(r"android\.subText=String \((.*?)\)", record)
                    when_match = re.search(r"when=(\d+)", record)

                    title = title_match.group(1) if title_match else None
                    text = big_text_match.group(1) if big_text_match else (text_match.group(1) if text_match else None)
                    sub = sub_match.group(1) if sub_match else None
                    timestamp = None
                    if when_match:
                        try:
                            # millis a timestamp
                            ts = int(when_match.group(1)) / 1000.0
                            if ts > 0:
                                timestamp = datetime.fromtimestamp(ts).strftime("%Y-%m-%d %H:%M")
                        except Exception:
                            pass

                    if title or text:
                        results[key].append({
                            "title": title or "Notificación",
                            "text": text or "",
                            "subText": sub or "",
                            "timestamp": timestamp or "Reciente"
                        })

        return results

    def get_portal_stats(self) -> Dict[str, Dict[str, Any]]:
        """Obtiene estadísticas de eventos/notificaciones por portal desde ADB."""
        raw = self._run_adb(["shell", "dumpsys", "notification"], timeout=20)
        stats: Dict[str, Dict[str, Any]] = {}

        for key, config in PORTALS.items():
            pkg = config["package"]
            enqueued = 0
            posted = 0
            if raw:
                match = re.search(rf"key='{re.escape(pkg)}'.*?numEnqueuedByApp=(\d+).*?numPostedByApp=(\d+)", raw, re.DOTALL)
                if match:
                    enqueued = int(match.group(1))
                    posted = int(match.group(2))

            stats[key] = {
                "name": config["name"],
                "icon": config["icon"],
                "package": pkg,
                "installed": True,
                "total_events_enqueued": enqueued,
                "currently_posted": posted
            }
        return stats

    def get_full_report(self) -> Dict[str, Any]:
        """Genera el reporte consolidado para Ancla."""
        device = self.get_connected_device()
        notifications = self.get_device_notifications() if device else {}
        stats = self.get_portal_stats() if device else {}

        # Cargar postulaciones registradas
        applications = []
        if APPLICATIONS_FILE.exists():
            try:
                with open(APPLICATIONS_FILE, "r", encoding="utf-8") as f:
                    data = json.load(f)
                    applications = data.get("applications", [])
            except Exception:
                pass

        return {
            "device_connected": device is not None,
            "device_id": device,
            "portals": stats,
            "live_notifications": notifications,
            "tracked_applications": applications
        }

    def format_cli_report(self) -> str:
        report = self.get_full_report()
        lines = []
        lines.append("💼 Estado de Cuentas de Búsqueda de Empleo")
        lines.append(f"📱 Dispositivo conectado: {'✅ Sí (' + report['device_id'] + ')' if report['device_connected'] else '❌ No detectado'}")
        lines.append("-" * 65)

        for key, config in PORTALS.items():
            portal_stat = report["portals"].get(key, {})
            notifs = report["live_notifications"].get(key, [])
            icon = config["icon"]
            name = config["name"]
            event_count = portal_stat.get("total_events_enqueued", 0)

            lines.append(f"{icon} {name}:")
            lines.append(f"   Actividad en dispositivo: {event_count} eventos registrados.")

            if notifs:
                lines.append("   🔔 Notificaciones activas:")
                for n in notifs:
                    sub_str = f" [{n['subText']}]" if n['subText'] else ""
                    lines.append(f"    - [{n['timestamp']}] {n['title']}{sub_str}: {n['text']}")
            else:
                lines.append("   (Sin notificaciones pendientes en la bandeja)")
            lines.append("")

        lines.append("-" * 65)
        lines.append(f"📋 Postulaciones Registradas ({len(report['tracked_applications'])}):")
        for app in report["tracked_applications"]:
            lines.append(f" - [{app['status']}] {app['role']} en {app['company']} ({app['portal']}) - {app.get('date', '')}")

        return "\n".join(lines)


def main():
    tracker = JobTracker()
    print(tracker.format_cli_report())


if __name__ == "__main__":
    main()
