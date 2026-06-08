# RubyDung Sound Prompts

## Шаги по траве / грунту
```
Short footstep sound on grass or dirt, soft thud, 8-bit game style, 0.2 seconds, mono
```

## Шаги по камню
```
Short footstep sound on stone or gravel, crisp click, 8-bit retro game, 0.15 seconds, mono
```

## Шаги по дереву
```
Wooden footstep, hollow thump, retro game sound effect, 0.2 seconds, mono
```

## Шаги по песку
```
Soft sandy footstep, muffled crunch, retro 8-bit style, 0.2 seconds, mono
```

## Шаги по воде / в воде
```
Splashing water footstep, light splash, retro game style, 0.25 seconds, mono
```

## Разрушение блока (грунт/трава)
```
Block breaking sound, dirt crumble, short burst, retro game sfx, 0.3 seconds, mono
```

## Разрушение блока (камень)
```
Stone block breaking sound, short crack and crumble, retro 8-bit game, 0.3 seconds, mono
```

## Разрушение блока (дерево)
```
Wood block breaking sound, short wooden crack, retro game style, 0.25 seconds, mono
```

## Постановка блока
```
Block placement sound, soft thud, retro game sound effect, 0.15 seconds, mono
```

## Постановка блока (камень)
```
Stone block placement, hard thud click, retro 8-bit game, 0.15 seconds, mono
```

## Удар по блоку (майнинг)
```
Digging hit sound, single impact on stone, retro game sfx, 0.1 seconds, mono
```

## Плеск воды (прыжок в воду)
```
Splash sound effect, jumping into water, retro game style, 0.4 seconds, mono
```

## Плавание
```
Gentle underwater bubbling loop, retro 8-bit game ambient, 1 second loop, mono
```

## Прыжок
```
Jump whoosh sound, light upward hop, retro game sound effect, 0.2 seconds, mono
```

## Приземление с высоты
```
Landing thud sound, heavy fall impact on ground, retro 8-bit game, 0.2 seconds, mono
```

## Меню открыть / закрыть
```
UI click sound, menu open, retro 8-bit interface sfx, 0.1 seconds, mono
```

## Нажатие кнопки в меню
```
Short button click sound, UI interaction, retro pixel game, 0.08 seconds, mono
```

## Подбор предмета
```
Item pickup sound, short chime ding, retro game style, 0.2 seconds, mono
```

## Крафт успешный
```
Crafting success sound, short positive chime, retro 8-bit game, 0.3 seconds, mono
```

## Дождь (амбиент)
```
Rain ambient sound, light rainfall, outdoor, retro lo-fi game atmosphere, 3 second loop, mono
```

## Ветер (амбиент)
```
Wind ambient sound, gentle breeze, outdoor open world, retro lo-fi, 4 second loop, mono
```

## Пещера (амбиент)
```
Cave drip ambience, dark underground atmosphere, retro game mood, 3 second loop, mono
```

---

## Как использовать

Все звуки генерировать как `.ogg` или `.wav`, 22050 Hz или 44100 Hz, mono.
Положить в `resources/sounds/` с именами:
- `step_grass.ogg`, `step_stone.ogg`, `step_wood.ogg`, `step_sand.ogg`, `step_water.ogg`
- `dig_grass.ogg`, `dig_stone.ogg`, `dig_wood.ogg`
- `place_soft.ogg`, `place_hard.ogg`
- `dig_hit.ogg`
- `splash.ogg`, `swim.ogg`
- `jump.ogg`, `land.ogg`
- `menu_open.ogg`, `click.ogg`
- `pickup.ogg`, `craft.ogg`
- `rain.ogg`, `wind.ogg`, `cave.ogg`

## Рекомендуемые нейронки
- **ElevenLabs** — Sound Effects (sfx генерация по тексту)
- **Suno / Udio** — для амбиентных луп
- **AudioCraft (Meta)** — локально, AudioGen модель
- **Stable Audio** — для атмосферных звуков
