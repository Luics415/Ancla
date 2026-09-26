#!/usr/bin/env python3
"""
Pruebas Unitarias para la Suite de Integración de Ancla.
"""

import unittest
import sys
from pathlib import Path

# Agregar directorio ancla al path
ANCLA_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ANCLA_DIR))

from github_manager import GitHubManager
from jules_bridge import JulesBridge
from job_tracker import JobTracker
from google_hub import GoogleHub
from ancla_orchestrator import AnclaOrchestrator


class TestAnclaSuite(unittest.TestCase):
    def setUp(self):
        self.orchestrator = AnclaOrchestrator()

    def test_github_public_repos(self):
        repos = self.orchestrator.github.get_public_repos()
        self.assertIsInstance(repos, list)
        self.assertGreaterEqual(len(repos), 20)

    def test_github_starred_repos(self):
        starred = self.orchestrator.github.get_starred_repos()
        self.assertIsInstance(starred, list)
        self.assertGreaterEqual(len(starred), 10)

    def test_find_repo_fuzzy(self):
        repo = self.orchestrator.github.find_repo("dev visualizer")
        self.assertIsNotNone(repo)
        self.assertEqual(repo["name"], "Dev-Visualizer")

        repo2 = self.orchestrator.github.find_repo("qrvoxelstudio")
        self.assertIsNotNone(repo2)
        self.assertEqual(repo2["name"], "QRVoxelStudio")

    def test_jules_bridge_auth_check(self):
        auth = self.orchestrator.jules.check_auth()
        self.assertIn("authenticated", auth)
        self.assertIn("message", auth)

    def test_job_tracker_device_or_fallback(self):
        report = self.orchestrator.jobs.get_full_report()
        self.assertIn("portals", report)
        self.assertIn("linkedin", report["portals"])
        self.assertIn("occ", report["portals"])

    def test_orchestrator_query_overview(self):
        res = self.orchestrator.handle_query("Ancla, como van mis proyectos")
        self.assertIn("Ancla - Estado General de tus Proyectos", res)
        self.assertIn("Luics415", res)

    def test_orchestrator_learning(self):
        learn_res = self.orchestrator.handle_query("Ancla, recuerda que me enfoco en Flutter y Jetpack Compose")
        self.assertIn("He aprendido y guardado", learn_res)
        has_learned = any("flutter y jetpack compose" in ins for ins in self.orchestrator.memory.memory["learned_insights"])
        self.assertTrue(has_learned)

    def test_bts_mode_trigger(self):
        res = self.orchestrator.handle_query("7777")
        self.assertIn("EXPERIENCIA INTERACTIVA ANCLA: BTS ARMY EDITION", res)
        self.assertIn("7777", res)

    def test_orchestrator_query_stars(self):
        res = self.orchestrator.handle_query("Que proyectos se encuentran en Stars/Favoritos")
        self.assertIn("Proyectos en Stars / Favoritos", res)

    def test_orchestrator_query_jobs(self):
        res = self.orchestrator.handle_query("como van mis cuentas de buscar empleo")
        self.assertIn("Estado de Cuentas de Búsqueda de Empleo", res)


if __name__ == "__main__":
    unittest.main()
