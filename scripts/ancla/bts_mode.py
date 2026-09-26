#!/usr/bin/env python3
"""
Módulo Interactivo BTS ARMY Edition ("Unlock BTS" / "7777") para Ancla.
Desbloquea una experiencia inmersiva, energética y personalizada inspirada en
los 7 miembros de BTS, con estética Borahae (púrpura), sabiduría para desarrolladores,
y dinamismo de alta energía en cada respuesta.
"""

import sys
import random
from typing import Dict, Any, List, Tuple

if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

# Códigos de color ANSI para experiencia visual púrpura / magenta
PURPLE = "\033[38;2;170;90;240m"
BOLD_PURPLE = "\033[1;38;2;190;110;255m"
CYAN = "\033[38;2;100;220;255m"
GOLD = "\033[38;2;255;215;0m"
RESET = "\033[0m"

BTS_MEMBERS_WISDOM = [
    ("RM (Kim Namjoon)", "Liderazgo & Visión", "No te preocupes por el camino largo. Cada línea de código que escribes hoy es el cimiento de la arquitectura de tu futuro."),
    ("Jin (Kim Seokjin)", "Confianza & Serenidad", "Incluso cuando el build falle, recuerda tu propio valor. Respira, depura con una sonrisa y vuelve a intentar."),
    ("SUGA / Agust D (Min Yoongi)", "Persistencia & Pasión", "El verdadero crecimiento técnico surge de las madrugadas donde no te rendiste ante un bug imposible."),
    ("J-Hope (Jung Hoseok)", "Energía & Ritmo", "Ponle alma, pasión y ritmo a lo que construyes. Que cada commit refleje la mejor versión de tu talento."),
    ("Jimin (Park Jimin)", "Detalle & Excelencia", "La diferencia entre un buen proyecto y uno inolvidable está en el amor al detalle que le pones a la experiencia de usuario."),
    ("V (Kim Taehyung)", "Creatividad & Originalidad", "No programes solo para que funcione; programa con un toque único y artístico que nadie más pueda replicar."),
    ("Jung Kook (Jeon Jungkook)", "El Golden Maknae - Enfoque Total", "Comienza con determinación, domina cada tecnología que te propongas y no te detengas hasta ver tus metas cumplidas.")
]


class BTSExperience:
    def __init__(self, memory_engine=None):
        self.memory = memory_engine

    def is_trigger(self, query: str) -> bool:
        q = query.lower().strip()
        triggers = ["unlock bts", "7777", "borahae", "bts mode", "modo bts", "army mode"]
        return any(t in q for t in triggers)

    def activate_experience(self) -> str:
        """Activa la experiencia interactiva y guarda el estado en la memoria."""
        if self.memory:
            self.memory.memory["bts_mode_unlocked"] = True
            current_state = self.memory.memory.get("bts_mode_active", False)
            new_state = not current_state  # Toggle
            self.memory.memory["bts_mode_active"] = new_state
            self.memory.learn_insight("Experiencia interactiva BTS ARMY Edition (7777 / Borahae) desbloqueada por el usuario.")
            self.memory._save_memory()
        else:
            new_state = True

        member, role, quote = random.choice(BTS_MEMBERS_WISDOM)

        lines = []
        lines.append(f"{BOLD_PURPLE}💜 ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ 💜{RESET}")
        lines.append(f"{BOLD_PURPLE}       ✨ ¡EXPERIENCIA INTERACTIVA ANCLA: BTS ARMY EDITION! ✨{RESET}")
        lines.append(f"{BOLD_PURPLE}💜 ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ 💜{RESET}")
        lines.append("")
        lines.append(f"{PURPLE}  ██████╗ ████████╗███████╗    █████╗ ██████╗ ███╗   ███╗██╗   ██╗{RESET}")
        lines.append(f"{PURPLE}  ██╔══██╗╚══██╔══╝██╔════╝   ██╔══██╗██╔══██╗████╗ ████║╚██╗ ██╔╝{RESET}")
        lines.append(f"{PURPLE}  ██████╔╝   ██║   ███████╗   ███████║██████╔╝██╔████╔██║ ╚████╔╝ {RESET}")
        lines.append(f"{PURPLE}  ██╔══██╗   ██║   ╚════██║   ██╔══██║██╔══██╗██║╚██╔╝██║  ╚██╔╝  {RESET}")
        lines.append(f"{PURPLE}  ██████╔╝   ██║   ███████║   ██║  ██║██║  ██║██║ ╚═╝ ██║   ██║   {RESET}")
        lines.append(f"{PURPLE}  ╚═════╝    ╚═╝   ╚══════╝   ╚═╝  ╚═╝╚═╝  ╚═╝╚═╝     ╚═╝   ╚═╝   {RESET}")
        lines.append("")
        lines.append(f"  🌟 Estado del Modo BTS: {'💜 [ACTIVADO - Máxima Energía]' if new_state else '⚪ [DESACTIVADO - Modo Estándar]'}")
        lines.append(f"  🔢 Clave Secreta Reconocida: '7777' / 'Unlock BTS'")
        lines.append(f"  🎨 Paleta Activa: Borahae Violet (#9B59B6 / #8A2BE2)")
        lines.append("")
        lines.append(f"{GOLD}👑 Sabiduría del Día para el Developer:{RESET}")
        lines.append(f"  🗣️ {CYAN}{member}{RESET} · {role}")
        lines.append(f"  💬 \"{quote}\"")
        lines.append("")
        lines.append(f"{PURPLE}🐾 Repositorios Especiales Reconocidos en tu Perfil:{RESET}")
        lines.append(f"  ⭐ {BOLD_PURPLE}Luics415/GX-Pets-ARMY-Edition{RESET} -> https://github.com/Luics415/GX-Pets-ARMY-Edition")
        lines.append(f"  ⭐ {BOLD_PURPLE}Luics415/GX-Pets{RESET} -> https://github.com/Luics415/GX-Pets")
        lines.append("")
        lines.append(f"🎉 {BOLD_PURPLE}¡A partir de ahora Ancla te responderá con energía renovada, toques púrpuras y motivación continua!{RESET}")
        lines.append(f"{BOLD_PURPLE}💜 ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ 💜{RESET}")

        return "\n".join(lines)

    def enhance_response(self, original_response: str) -> str:
        """Añade toques de energía y estilo Borahae a las respuestas normales."""
        if not self.memory or not self.memory.memory.get("bts_mode_active", False):
            return original_response

        header = f"{BOLD_PURPLE}💜 [Ancla · ARMY Edition 7777]{RESET}\n"
        footer = f"\n{BOLD_PURPLE}✨ Borahae! Sigamos construyendo grandes proyectos con pasión. 💜{RESET}"
        return f"{header}{original_response}{footer}"


def main():
    exp = BTSExperience()
    print(exp.activate_experience())


if __name__ == "__main__":
    main()
