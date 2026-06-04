#!/usr/bin/env bash

_h2_longrun_option_takes_value() {
    case "$1" in
        --config|-c|--work-dir|-w|--log-file|-l|--min-ops-per-second|-n|--max-throughput-drop-ratio|-t|--max-final-size-gb|-f|--max-size-per-million-ops-gb|-p|--max-size-amplification|-a|--min-reclamation-events|-e|--max-error-lines|-x|--duration|-d|--seed|-s|--mode|-m|--resume|-R|--worker|-k|--h2-jar|-j)
            return 0
            ;;
        *)
            return 1
            ;;
    esac
}

_h2_longrun_complete_words() {
    local cur candidate
    cur=$1
    shift
    COMPREPLY=()
    for candidate in "$@"; do
        [[ $candidate == $cur* ]] && COMPREPLY+=("$candidate")
    done
}

_h2_longrun_complete_value() {
    local cur option
    cur=$1
    option=$2

    case "$option" in
        --config|-c)
            COMPREPLY=( $(compgen -f -X '!*.[Pp][Rr][Oo][Pp][Ee][Rr][Tt][Ii][Ee][Ss]' -- "$cur") )
            ;;
        --work-dir|-w)
            COMPREPLY=( $(compgen -d -- "$cur") )
            ;;
        --log-file|-l|--h2-jar|-j)
            COMPREPLY=( $(compgen -f -- "$cur") )
            ;;
        --mode|-m)
            _h2_longrun_complete_words "$cur" mvstore sql mixed
            ;;
        --resume|-R|--worker|-k)
            _h2_longrun_complete_words "$cur" true false
            ;;
        *)
            COMPREPLY=()
            ;;
    esac
}

_h2_longrun_get_command() {
    local args previous
    local command=
    local command_index=-1
    args=("$@")
    previous=

    for ((i = 1; i < ${#args[@]}; i++)); do
        local arg
        arg="${args[i]}"

        if [ -n "$previous" ]; then
            previous=
            continue
        fi

        if _h2_longrun_option_takes_value "$arg"; then
            previous="$arg"
            continue
        fi

        case "$arg" in
            start|run|stop|status|restart|logs|watch|report|help|--help|-h)
                command=$arg
                command_index=$i
                break
                ;;
            --rotate-log|--append-log|--truncate-log)
                ;;
            *)
                if [[ "$arg" != --* ]]; then
                    command=$arg
                    command_index=$i
                    break
                fi
                ;;
        esac
    done

    echo "$command|$command_index"
}

_h2_longrun_complete_options() {
    local command=$1
    local base_opts
    base_opts=(
        --help -h --config -c --work-dir -w --log-file -l --duration -d
        --mode -m --seed -s --h2-jar -j
        --min-ops-per-second -n --max-throughput-drop-ratio -t
        --max-final-size-gb -f --max-size-per-million-ops-gb -p
        --max-size-amplification -a --min-reclamation-events -e
        --max-error-lines -x --resume -R --worker -k
    )
    if [ "$command" != report ]; then
        base_opts+=(--rotate-log --append-log --truncate-log)
    fi
    _h2_longrun_complete_words "$cur" "${base_opts[@]}"
}

_h2_longrun() {
    local cur prev state command command_index
    cur=${COMP_WORDS[COMP_CWORD]}
    prev=${COMP_WORDS[COMP_CWORD-1]}
    state=$(_h2_longrun_get_command "${COMP_WORDS[@]}")
    command=${state%|*}
    command_index=${state#*|}

    if [ "$COMP_CWORD" -ge 1 ] && _h2_longrun_option_takes_value "$prev"; then
        _h2_longrun_complete_value "$cur" "$prev"
        return 0
    fi

    if [ -z "$command" ]; then
        if [[ "$cur" == -* ]]; then
            _h2_longrun_complete_words "$cur" start run stop status restart logs watch report help \
                --help -h --rotate-log --append-log --truncate-log
        else
            _h2_longrun_complete_words "$cur" start run stop status restart logs watch report help
        fi
        return 0
    fi

    if [ "$command_index" -eq "$COMP_CWORD" ]; then
        _h2_longrun_complete_words "$cur" start run stop status restart logs watch report help --help -h
        return 0
    fi

    if [[ "$cur" == -* ]]; then
        _h2_longrun_complete_options "$command"
    else
        COMPREPLY=()
    fi
}

complete -F _h2_longrun -o default -o bashdefault h2-longrun ./bin/h2-longrun
